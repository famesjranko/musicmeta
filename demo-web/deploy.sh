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
#   --max=1            instance is a second gate, so the bound it advertises becomes a lie, and
#                      nothing in the process can read this setting to notice. It is also the only
#                      real spend ceiling: Google offers no hard cap, and a budget is an alert, not
#                      a brake. BOTH are required: --max-instances is the per-revision cap
#                      (autoscaling.knative.dev/maxScale), --max is the service-level cap
#                      (run.googleapis.com/maxScale, what the console "Scaling" shows). The
#                      service-level one defaults to 20 and governs, so setting only the revision
#                      cap silently leaves the service able to scale to 20.
#   --concurrency=8    Measured, not guessed. At concurrency 1 the platform queues a second visitor
#                      for eleven seconds and then refuses them in plain text, which no page can
#                      render. At 8 the request reaches the application, where the gate refuses it
#                      in under a second with a JSON body and a Retry-After. Raising it does not
#                      cost result quality: ten simultaneous distinct lookups returned complete
#                      results with zero timeouts.
#
# Public vs private is a separate, deliberate act, NOT this script's: it is a run.invoker binding for
# allUsers, granted once and cannot be quietly undone once the URL is known. This deploy passes
# neither --allow-unauthenticated nor --no-allow-unauthenticated, so it leaves that binding exactly
# as it found it — a redeploy never flips a public demo private (or vice versa) behind your back.
# Go public:  gcloud run services add-iam-policy-binding <service> --region <r> --member=allUsers --role=roles/run.invoker
# Go private: the same with remove-iam-policy-binding.
#
# One-time account setup (runtime service account, secret creation + IAM grants) is the operator's
# to run knowingly — this script does not create SAs, secrets, or IAM bindings. The demo needs a
# dedicated least-privilege runtime SA (no project role) holding secretAccessor on the demo's
# Secret Manager secrets: the provider keys, the maintainer secret, and the posture secret.
set -euo pipefail
cd "$(dirname "$0")/.." || exit 1

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

# Deployment target. The project, region and service name are identifiers this public repo does not
# carry: set them in the gitignored secrets.properties (demo.gcp.project / .region / .service), or
# pass them as env vars, which win. All three are required for a real deploy.
PROJECT="${PROJECT:-$(secret_prop demo.gcp.project)}"
REGION="${REGION:-$(secret_prop demo.gcp.region)}"
SERVICE="${SERVICE:-$(secret_prop demo.gcp.service)}"
for v in PROJECT REGION SERVICE; do
    if [ -z "${!v}" ]; then
        echo "deploy.sh: $v is unset — add demo.gcp.$(printf '%s' "$v" | tr '[:upper:]' '[:lower:]') to secrets.properties, or export $v." >&2
        exit 64
    fi
done
TAG="${TAG:-latest}"
IMAGE="${REGION}-docker.pkg.dev/${PROJECT}/demo/musicmeta-demo-web:${TAG}"

# The dedicated least-privilege runtime SA (created out-of-band by the operator). It
# holds secretAccessor on only the demo's secrets and no project role, unlike the default compute SA
# that Cloud Run would otherwise use, which carries project-wide `roles/editor`. Override via env or
# secrets.properties (demo.gcp.service_account); otherwise derived from the project.
SERVICE_ACCOUNT="${SERVICE_ACCOUNT:-$(secret_prop demo.gcp.service_account)}"
SERVICE_ACCOUNT="${SERVICE_ACCOUNT:-musicmeta-demo-run@${PROJECT}.iam.gserviceaccount.com}"

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
    --max=1 \
    --min-instances=0 \
    --cpu=1 \
    --memory=512Mi \
    --timeout=180 \
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
# nothing else flags. Assert one container so that fails loud here; the fix is a one-time `gcloud run
# services replace` with the extra container edited out. Count `.image` (every container has one)
# rather than `.name`, which a single unnamed ingress container leaves empty.
CONTAINERS="$(CLOUDSDK_CORE_PROJECT="$PROJECT" gcloud run services describe "$SERVICE" \
    --region "$REGION" \
    --format='value(spec.template.spec.containers[].image)')"
CONTAINER_COUNT="$(printf '%s' "$CONTAINERS" | tr ';' '\n' | grep -c '.')"
if [ "$CONTAINER_COUNT" -ne 1 ]; then
    echo "deploy.sh: served revision has $CONTAINER_COUNT containers, expected exactly 1: $CONTAINERS" >&2
    echo "deploy.sh: this is the orphan-sidecar defect — 'gcloud run deploy --image' cannot remove it." >&2
    echo "deploy.sh: fix once: 'gcloud run services describe $SERVICE --region $REGION --format=export'," >&2
    echo "deploy.sh: keep only the ingress container, then 'gcloud run services replace' the edited YAML." >&2
    exit 1
fi
