#!/usr/bin/env bash
# Deploy the weather dashboard to Google Cloud Run.
#
# Required environment:
#   GOOGLE_CLOUD_PROJECT   Google Cloud project ID (billing enabled)
#   GOOGLE_MAPS_API_KEY    Maps Platform key with the Weather API enabled
# Optional:
#   GOOGLE_CLOUD_CREDENTIALS_JSON   service-account key JSON; if set, used to log in
#   REGION   (default us-east1)   SERVICE (default ny-weather)   MIN_INSTANCES (default 0)
#
# Usage: ./deploy.sh

set -euo pipefail

: "${GOOGLE_CLOUD_PROJECT:?Set GOOGLE_CLOUD_PROJECT to your project ID}"
: "${GOOGLE_MAPS_API_KEY:?Set GOOGLE_MAPS_API_KEY to your Weather API key}"
REGION="${REGION:-us-east1}"
SERVICE="${SERVICE:-ny-weather}"
MIN_INSTANCES="${MIN_INSTANCES:-0}"

cd "$(dirname "$0")"

if [[ -n "${GOOGLE_CLOUD_CREDENTIALS_JSON:-}" ]]; then
  keyfile="$(mktemp)"
  trap 'rm -f "$keyfile"' EXIT
  printf '%s' "$GOOGLE_CLOUD_CREDENTIALS_JSON" > "$keyfile"
  gcloud auth activate-service-account --key-file="$keyfile" --quiet
fi

gcloud config set project "$GOOGLE_CLOUD_PROJECT" --quiet

echo "==> Enabling required APIs"
gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com --quiet

echo "==> Deploying $SERVICE to Cloud Run ($REGION)"
gcloud run deploy "$SERVICE" \
  --source . \
  --region "$REGION" \
  --platform managed \
  --allow-unauthenticated \
  --memory 256Mi \
  --cpu 1 \
  --min-instances "$MIN_INSTANCES" \
  --max-instances 2 \
  --concurrency 80 \
  --set-env-vars "GOOGLE_MAPS_API_KEY=${GOOGLE_MAPS_API_KEY},CACHE_TTL_SECONDS=600,MIN_REFRESH_SECONDS=60" \
  --quiet

URL="$(gcloud run services describe "$SERVICE" --region "$REGION" --format 'value(status.url)')"
echo
echo "==> Deployed: $URL"
echo "    Health:  $URL/healthz"
