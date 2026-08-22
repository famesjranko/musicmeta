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
# 4. After the SA exists and holds exactly those five bindings, confirm it has NO project-level
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
# 5. One-time cleanup for the orphan sidecar (see the note above the container-count assertion
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

# The dedicated runtime SA created by the USER RUNS THESE block above. It holds secretAccessor
# on exactly the four provider-key secrets plus the maintainer secret, and no project role — the
# default compute SA this replaces carries project-wide `roles/editor`, which a demo that only
# reads five secrets has no business holding.
SERVICE_ACCOUNT="${SERVICE_ACCOUNT:-musicmeta-demo-run@${PROJECT}.iam.gserviceaccount.com}"

BUILD=1
for arg in "$@"; do
    case "$arg" in
        --no-build) BUILD=0 ;;
        *) echo "usage: ./deploy.sh [--no-build]" >&2; exit 64 ;;
    esac
done

if [ "$BUILD" = 1 ]; then
    # From the repository root: demo-web is a composite build that resolves musicmeta-core from
    # local source, so the build context has to contain both.
    docker build -f demo-web/Dockerfile -t "$IMAGE" .
    docker push "$IMAGE"
fi

# CLOUDSDK_CORE_PROJECT rather than `gcloud config set project`: this leaves the caller's ambient
# configuration exactly as it found it.
#
# DEMO_PUBLIC=1 belongs to the deploy, not to whoever runs it: the provider posture a hosted
# instance owes its upstreams cannot depend on an operator remembering an export. `--update-env-vars`
# rather than `--set-env-vars` so it adds to whatever else the service already carries.
#
# DEMO_PUBLIC_ALLOW=all is a deliberate maintainer choice, not a default: it lifts every
# PublicRelaxation (Last.fm without prior written approval — API ToS 2.7 — plus exposing the
# maintainer's personal ListenBrainz token, Discogs images, and Discogs's 6h freshness cap) on a
# URL that is, or may become, publicly reachable. The maintainer has accepted that ToS and
# credential-exposure risk knowingly for this instance. To narrow it later WITHOUT a rebuild:
# either unset DEMO_PUBLIC_ALLOW entirely (all restrictions back in force) or set it to a
# comma-separated subset of the relaxation ids (see PublicRelaxation in PublicPosture.kt, and
# demo-web/README.md) via `gcloud run services update musicmeta-demo --update-env-vars
# DEMO_PUBLIC_ALLOW=<subset>` — no new image, no redeploy through this script required.
#
# --service-account pins the runtime identity to the least-privilege SA above; without it Cloud
# Run defaults to the project's compute SA, which holds `roles/editor` — far more than a
# secret-reading demo should ever run as.
#
# DEMO_MAINTAINER_SECRET is bound from Secret Manager, never a literal: WP1's `/api/config` POST
# gate compares this value against the `X-Maintainer-Secret` request header. `--update-secrets`
# (additive, like `--update-env-vars`) rather than `--set-secrets` so a prior manual binding for
# the four provider-key secrets is left untouched by this script.
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
    --update-env-vars DEMO_PUBLIC=1,DEMO_PUBLIC_ALLOW=all \
    --update-secrets=DEMO_MAINTAINER_SECRET=demo-maintainer-secret:latest \
    --quiet

# A deploy that returns zero and serves 503 is a failed deploy; only the readback tells them apart.
CLOUDSDK_CORE_PROJECT="$PROJECT" gcloud run services describe "$SERVICE" \
    --region "$REGION" \
    --format='value[separator="  "](status.latestReadyRevisionName,spec.template.spec.containerConcurrency,spec.template.metadata.annotations["autoscaling.knative.dev/maxScale"])'

# The Dockerfile defines exactly one container (one CMD). A stale `gcloud run services replace`
# with a multi-container spec, at some point in this service's history, added a second, portless
# 'app' container to the revision template — `gcloud run deploy --image` updates the ingress
# container in place but does not delete a sidecar already sitting in the template, so the orphan
# rides forward on every deploy since. This assertion catches that recurring instead of trusting
# a human to notice a doubled Cloud Run bill: fail loud, here, rather than silently serving a
# revision nobody asked for.
CONTAINERS="$(CLOUDSDK_CORE_PROJECT="$PROJECT" gcloud run services describe "$SERVICE" \
    --region "$REGION" \
    --format='value(spec.template.spec.containers[].name)')"
CONTAINER_COUNT="$(printf '%s' "$CONTAINERS" | tr ';' '\n' | grep -c '.')"
if [ "$CONTAINER_COUNT" -ne 1 ]; then
    echo "deploy.sh: served revision has $CONTAINER_COUNT containers, expected exactly 1: $CONTAINERS" >&2
    echo "deploy.sh: this is the orphan-sidecar defect — 'gcloud run deploy --image' cannot remove it;" >&2
    echo "deploy.sh: see the one-time cleanup ('services replace' with a single-container YAML) in the" >&2
    echo "deploy.sh: USER RUNS THESE block at the top of this script." >&2
    exit 1
fi
