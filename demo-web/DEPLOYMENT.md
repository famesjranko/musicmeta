# Deploying demo-web

How the public demo is built, deployed, and — the part worth keeping straight — kept from ever
costing much. Operator-only values (billing-account id, secret contents, the exact IAM grants) live
in the maintainer's local runbooks, never in this repo.

The live instance is a single [Cloud Run](https://cloud.google.com/run) service that scales to zero
when idle. Its project, region, and service name are deployment configuration, not committed here —
`deploy.sh` reads them from the gitignored `secrets.properties` (`demo.gcp.project` / `.region` /
`.service`), or from environment variables of the same name. This doc uses `<PROJECT>`, `<REGION>`,
and `<SERVICE>` as placeholders for them.

## Deploying

`./deploy.sh` from the repo root builds the container, pushes it, and deploys the revision. Flags
that matter are documented inline in `deploy.sh`; the load-bearing ones are `--max-instances=1` and
`--concurrency=8`, and they are not casual knobs (see the comments there).

**Build source.** The image builds `musicmeta-core` either from this checkout or from a published
Maven Central release, chosen by `demo.core.version` in the gitignored `secrets.properties`:

- empty / unset → build core from local source (the only image that compiles while `demo-web`
  uses `[Unreleased]` core API);
- a version (e.g. `0.13.0`) → pin that released core.

Flip the deployed build source by changing that one value and rerunning `deploy.sh` — no other edit.

**Provider posture.** `DEMO_PUBLIC=1` puts the instance in a terms-of-service-safe posture for a
public URL (see `demo-web/README.md`). Which restrictions are lifted is read from a Secret Manager
secret bound as `DEMO_PUBLIC_ALLOW`, not committed here — so the repo never carries a value that
would reproduce a permissive instance. Flip it by adding a secret version (`all` / `none` / a
subset) and redeploying; no rebuild.

> Migration note: Cloud Run refuses to change an existing key from a plain env var to a secret (or
> back) in a single update — `Cannot update environment variable [X] ... already set with a
> different type`. A service that once set `DEMO_PUBLIC_ALLOW` as an env var needs a one-time
> `gcloud run services update <SERVICE> --region <REGION> --remove-env-vars DEMO_PUBLIC_ALLOW`
> before the first secret-bound deploy.

## Keeping it cheap

The demo is built to cost ~$0 and to bound its own worst case. The parts visible in this repo:

- `--max-instances=1` — the service never scales past one instance, so there is no runaway-scale
  bill; combined with `--min-instances=0` and request-based billing, an idle instance costs nothing.
- An in-process admission gate refuses excess concurrent load with a fast `429` instead of fanning
  out expensive upstream calls, so a hammering client generates near-zero cost.
- `robots.txt` disallows `/api/`, keeping well-behaved crawlers off the one costly path.
- Both Artifact Registry repositories auto-prune (keep the three newest images, drop untagged ones
  older than 30 days). Old Cloud Run revisions are left in place — free at rest, and handy for an
  instant rollback.

Additional account-level cost controls (budget monitoring and related billing-account settings) are
configured out-of-band by the maintainer and kept out of this repo, since they involve the billing
account. Tearing the service down stops all of its spend immediately:

```
gcloud run services delete <SERVICE> --region <REGION>
```

## One-time account setup

`deploy.sh` does not — and must not — create service accounts, secrets, or IAM bindings; those are
the operator's to run knowingly. The steps below are generic (substitute your own `<PROJECT>` and
values); the concrete identifiers live only in your `secrets.properties`.

Create a dedicated least-privilege runtime service account (no project role):

```
gcloud iam service-accounts create <SA-NAME> --project=<PROJECT> \
  --display-name="musicmeta demo Cloud Run runtime"
```

Grant it `secretAccessor` on only the secrets the demo reads — the provider keys, the maintainer
secret, and the posture secret — one binding each, never a project-wide role:

```
for S in <provider-key-secrets> demo-maintainer-secret demo-public-allow; do
  gcloud secrets add-iam-policy-binding "$S" --project=<PROJECT> \
    --member="serviceAccount:<SA-NAME>@<PROJECT>.iam.gserviceaccount.com" \
    --role="roles/secretmanager.secretAccessor"
done
```

Create the maintainer secret (generate the value yourself — never hand a literal secret to a script
or an agent) and the posture secret (its value is the `DEMO_PUBLIC_ALLOW` token list — `all` /
`none` / a subset, not a credential; seed it `none`):

```
printf '%s' "$(openssl rand -base64 32)" | gcloud secrets create demo-maintainer-secret \
  --project=<PROJECT> --replication-policy=automatic --data-file=-
printf '%s' none | gcloud secrets create demo-public-allow \
  --project=<PROJECT> --replication-policy=automatic --data-file=-
```

Confirm the runtime SA holds **no** project-level role (in particular it must not inherit the
default compute SA's `roles/editor`):

```
gcloud projects get-iam-policy <PROJECT> --flatten="bindings[].members" \
  --filter="bindings.members:<SA-NAME>@<PROJECT>.iam.gserviceaccount.com" \
  --format='table(bindings.role)'    # expect no output
```

**Orphan-sidecar cleanup** — only if `deploy.sh`'s container-count assertion ever fails. `gcloud run
deploy --image` cannot delete a sidecar already in the revision template; export it, edit out the
extra container, and replace:

```
gcloud run services describe <SERVICE> --region <REGION> --format=export > /tmp/svc.yaml
# keep ONLY the ingress container under spec.template.spec.containers, delete any other block
gcloud run services replace /tmp/svc.yaml --region <REGION>
```

Budget monitoring and account-level billing controls are configured separately and are not part of
this repo, since they involve the billing account.

If you are not the maintainer, you need none of this to run the demo locally — see
`demo-web/README.md`, which works with no keys at all.
