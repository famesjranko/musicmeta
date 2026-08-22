#!/usr/bin/env bash
# Build, push and deploy the web demo to Cloud Run.
#
# Usage:
#   ./deploy.sh                    # build, push, deploy
#   ./deploy.sh --no-build         # deploy the tag that is already in the registry
#   PROJECT=other ./deploy.sh      # a different project
#
# The flags below are not defaults anyone may adjust casually. Two of them are load-bearing, and
# the reasons are measured rather than assumed:
#
#   --max-instances=1  The admission gate in Server.kt is a per-instance semaphore. A second
#                      instance is a second gate, so the bound it advertises becomes a lie, and
#                      nothing in the process can read this setting to notice. It is also the only
#                      real spend ceiling: Google offers no hard cap, and a budget is an alert, not
#                      a brake.
#   --concurrency=8    Measured, not guessed. At concurrency 1 the platform queues a second visitor
#                      for eleven seconds and then refuses them in plain text, which no page can
#                      render. At 8 the request reaches the application, where the gate refuses it
#                      in under a second with a JSON body and a Retry-After. Raising it does not
#                      cost result quality: ten simultaneous distinct lookups returned complete
#                      results with zero timeouts.
#
# The service is private. Making it public is a separate, deliberate act — it grants run.invoker to
# allUsers and cannot be quietly undone once the URL is known — and provider terms of service have
# not been cleared for a public instance.
#
# --- USER RUNS THESE (one-time setup; this script does not, and must not, run gcloud commands
# --- that create or grant IAM — those are the operator's to execute knowingly) ---
#
# 1. Create the dedicated runtime service account (least-privilege: no project role, only
#    secretAccessor on the secrets this demo actually reads).
#
#   gcloud iam service-accounts create musicmeta-demo-run \
#     --project=musicmeta-demo-4821 \
#     --display-name="musicmeta demo Cloud Run runtime"
#
# 2. Grant that SA secretAccessor on ONLY the four provider-key secrets — one binding per
#    secret, never a project-wide role. Replace each <..._SECRET> with the actual Secret
#    Manager resource name for that key in this project (confirm with
#    `gcloud secrets list --project=musicmeta-demo-4821` — names below are the conventional
#    ones, not verified against the live project).
#
#   for S in lastfm-api-key fanarttv-api-key discogs-token listenbrainz-token; do
#     gcloud secrets add-iam-policy-binding "$S" \
#       --project=musicmeta-demo-4821 \
#       --member="serviceAccount:musicmeta-demo-run@musicmeta-demo-4821.iam.gserviceaccount.com" \
#       --role="roles/secretmanager.secretAccessor"
#   done
#
# 3. Create the maintainer secret (the value gated by WP1's `X-Maintainer-Secret` check) and
#    grant the same runtime SA access to it. Generate the value yourself — never hand this
#    script or an agent a literal secret:
#
#   printf '%s' "$(openssl rand -base64 32)" | gcloud secrets create demo-maintainer-secret \
#     --project=musicmeta-demo-4821 \
#     --replication-policy=automatic \
#     --data-file=-
#   gcloud secrets add-iam-policy-binding demo-maintainer-secret \
#     --project=musicmeta-demo-4821 \
#     --member="serviceAccount:musicmeta-demo-run@musicmeta-demo-4821.iam.gserviceaccount.com" \
#     --role="roles/secretmanager.secretAccessor"
#
# 4. Create the provider-posture secret and grant the same SA access. Its value is the
#    DEMO_PUBLIC_ALLOW token list, not a credential (`all` / `none` / a subset — see the deploy
#    step's comment). Seed it `none` (safe); add an `all` version when you want full providers:
#
#   printf '%s' none | gcloud secrets create demo-public-allow \
#     --project=musicmeta-demo-4821 \
#     --replication-policy=automatic \
#     --data-file=-
#   gcloud secrets add-iam-policy-binding demo-public-allow \
#     --project=musicmeta-demo-4821 \
#     --member="serviceAccount:musicmeta-demo-run@musicmeta-demo-4821.iam.gserviceaccount.com" \
#     --role="roles/secretmanager.secretAccessor"
#
# 5. After the SA exists and holds exactly those six bindings, confirm it has NO project-level
#    role (in particular, it must never inherit the default compute SA's `roles/editor`):
#
#   gcloud projects get-iam-policy musicmeta-demo-4821 \
#     --flatten="bindings[].members" \
#     --filter="bindings.members:musicmeta-demo-run@musicmeta-demo-4821.iam.gserviceaccount.com" \
#     --format='table(bindings.role)'
#   # expect: no output (no project-level bindings for this SA).
#
#   The default compute SA's `roles/editor` is a separate, project-wide concern (it isn't this
#   SA's problem to fix): removing it is a broader project decision the maintainer should make
#   independently of this script, since other Cloud Run services or workloads on the project may
#   still depend on it.
#
# 6. One-time cleanup for the orphan sidecar (see the note above the container-count assertion
#    below): if the assertion below ever fails, the live revision has an extra container the
#    Dockerfile does not define. `gcloud run deploy --image` cannot remove it — export, edit out
#    the extra container block, and replace:
#
#   gcloud run services describe musicmeta-demo --region us-central1 --format=export > /tmp/svc.yaml
#   # edit /tmp/svc.yaml: under spec.template.spec.containers, keep ONLY the ingress container
#   # (the one with the port / the musicmeta image) and delete any other container block.
#   gcloud run services replace /tmp/svc.yaml --region us-central1
#
# --- end USER RUNS THESE ---
set -euo pipefail
cd "$(dirname "$0")/.." || exit 1

PROJECT="${PROJECT:-musicmeta-demo-4821}"
REGION="${REGION:-us-central1}"
SERVICE="${SERVICE:-musicmeta-demo}"
TAG="${TAG:-latest}"
IMAGE="${REGION}-docker.pkg.dev/${PROJECT}/demo/musicmeta-demo-web:${TAG}"

# The dedicated runtime SA created by the USER RUNS THESE block above. It holds secretAccessor on
# exactly the four provider-key secrets, the maintainer secret and the posture secret, and no
# project role — the default compute SA this replaces carries project-wide `roles/editor`, which a
# demo that only reads six secrets has no business holding.
SERVICE_ACCOUNT="${SERVICE_ACCOUNT:-musicmeta-demo-run@${PROJECT}.iam.gserviceaccount.com}"

# Read one key out of the gitignored secrets.properties (repo root first, then demo-web/ — the
# nearer file wins, matching how the app itself resolves them). Comment lines and surrounding
# whitespace are ignored; a missing key yields empty.
secret_prop() {
    local key="$1" val="" line
    for f in secrets.properties demo-web/secrets.properties; do
        [ -f "$f" ] || continue
        line="$(grep -E "^[[:space:]]*${key}[[:space:]]*=" "$f" | grep -vE '^[[:space:]]*#' | tail -n1 || true)"
        [ -n "$line" ] && val="${line#*=}"
    done
    printf '%s' "$val" | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//'
}

# Which musicmeta-core the image is built against. Empty (or unset) builds core from the local
# checkout — the dev/test image, and the only one that compiles while demo-web uses `[Unreleased]`
# core API. A version string (e.g. 0.13.0) pins that published Maven Central release instead, for a
# reproducible image once a release carries every symbol demo-web needs. Set it in secrets.properties
# as `demo.core.version=` (empty = source, a version = Maven); an env var of the same name wins.
: "${DEMO_CORE_VERSION:=$(secret_prop demo.core.version)}"

BUILD=1
for arg in "$@"; do
    case "$arg" in
        --no-build) BUILD=0 ;;
        *) echo "usage: ./deploy.sh [--no-build]" >&2; exit 64 ;;
    esac
done

if [ "$BUILD" = 1 ]; then
    # From the repository root: the build context has to contain both demo-web and musicmeta-core
    # so a source build can resolve core from the checkout. DEMO_CORE_VERSION is passed explicitly
    # (empty = local source) rather than relying on the Dockerfile's default.
    if [ -n "$DEMO_CORE_VERSION" ]; then
        echo "deploy.sh: building demo-web core from Maven Central $DEMO_CORE_VERSION"
    else
        echo "deploy.sh: building demo-web core from local source"
    fi
    docker build -f demo-web/Dockerfile --build-arg DEMO_CORE_VERSION="$DEMO_CORE_VERSION" -t "$IMAGE" .
    docker push "$IMAGE"
fi

# CLOUDSDK_CORE_PROJECT rather than `gcloud config set project`: this leaves the caller's ambient
# configuration exactly as it found it.
#
# DEMO_PUBLIC=1 belongs to the deploy, not to whoever runs it: the provider posture a hosted
# instance owes its upstreams cannot depend on an operator remembering an export. `--update-env-vars`
# rather than `--set-env-vars` so it adds to whatever else the service already carries.
#
# DEMO_PUBLIC_ALLOW is bound from Secret Manager, not committed here: which restrictions a public
# instance lifts is a provider-ToS choice this script must not carry a value for, since a value in
# git is reproduced by every operator who runs it. The `demo-public-allow` secret holds the token
# list (see PublicRelaxation in PublicPosture.kt): `all` lifts every restriction, `none` lifts
# nothing (the safe posture — a token rather than an empty value, which Secret Manager rejects), or
# a comma-separated subset. Flip it WITHOUT a rebuild by adding a secret version and redeploying:
# `printf '%s' none | gcloud secrets versions add demo-public-allow --data-file=- && ./deploy.sh --no-build`.
#
# --service-account pins the runtime identity to the least-privilege SA above; without it Cloud
# Run defaults to the project's compute SA, which holds `roles/editor` — far more than a
# secret-reading demo should ever run as.
#
# DEMO_MAINTAINER_SECRET (the `/api/config` POST gate's expected `X-Maintainer-Secret`) and
# DEMO_PUBLIC_ALLOW are both bound from Secret Manager, never literals. `--update-secrets`
# (additive, like `--update-env-vars`) rather than `--set-secrets` so a prior manual binding for
# the provider-key secrets is left untouched by this script.
CLOUDSDK_CORE_PROJECT="$PROJECT" gcloud run deploy "$SERVICE" \
    --region "$REGION" \
    --image "$IMAGE" \
    --concurrency=8 \
    --max-instances=1 \
    --min-instances=0 \
    --cpu=1 \
    --memory=512Mi \
    --timeout=180 \
    --no-allow-unauthenticated \
    --service-account="$SERVICE_ACCOUNT" \
    --update-env-vars DEMO_PUBLIC=1 \
    --update-secrets=DEMO_MAINTAINER_SECRET=demo-maintainer-secret:latest,DEMO_PUBLIC_ALLOW=demo-public-allow:latest \
    --quiet

# Print the served revision's key facts for the operator to eyeball; `gcloud run deploy --quiet`
# above already fails the script on a deploy that does not go ready.
CLOUDSDK_CORE_PROJECT="$PROJECT" gcloud run services describe "$SERVICE" \
    --region "$REGION" \
    --format='value[separator="  "](status.latestReadyRevisionName,spec.template.spec.containerConcurrency,spec.template.metadata.annotations["autoscaling.knative.dev/maxScale"])'

# The Dockerfile defines exactly one container. `gcloud run deploy --image` updates the ingress
# container in place but cannot delete a sidecar already sitting in the revision template, so a
# multi-container template survives every image-only deploy — a second container is a doubled bill
# nothing else flags. Assert one container so that fails loud here; the USER RUNS THESE block has
# the one-time `services replace` cleanup. Count `.image` (every container has one) rather than
# `.name`, which a single unnamed ingress container leaves empty.
CONTAINERS="$(CLOUDSDK_CORE_PROJECT="$PROJECT" gcloud run services describe "$SERVICE" \
    --region "$REGION" \
    --format='value(spec.template.spec.containers[].image)')"
CONTAINER_COUNT="$(printf '%s' "$CONTAINERS" | tr ';' '\n' | grep -c '.')"
if [ "$CONTAINER_COUNT" -ne 1 ]; then
    echo "deploy.sh: served revision has $CONTAINER_COUNT containers, expected exactly 1: $CONTAINERS" >&2
    echo "deploy.sh: this is the orphan-sidecar defect — 'gcloud run deploy --image' cannot remove it;" >&2
    echo "deploy.sh: see the one-time cleanup ('services replace' with a single-container YAML) in the" >&2
    echo "deploy.sh: USER RUNS THESE block at the top of this script." >&2
    exit 1
fi
