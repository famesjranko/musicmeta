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
set -euo pipefail
cd "$(dirname "$0")/.." || exit 1

PROJECT="${PROJECT:-musicmeta-demo-4821}"
REGION="${REGION:-us-central1}"
SERVICE="${SERVICE:-musicmeta-demo}"
TAG="${TAG:-latest}"
IMAGE="${REGION}-docker.pkg.dev/${PROJECT}/demo/musicmeta-demo-web:${TAG}"

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
    --update-env-vars DEMO_PUBLIC=1 \
    --quiet

# A deploy that returns zero and serves 503 is a failed deploy; only the readback tells them apart.
CLOUDSDK_CORE_PROJECT="$PROJECT" gcloud run services describe "$SERVICE" \
    --region "$REGION" \
    --format='value[separator="  "](status.latestReadyRevisionName,spec.template.spec.containerConcurrency,spec.template.metadata.annotations["autoscaling.knative.dev/maxScale"])'
