# Local fork of Floci

This is a fork of [floci-io/floci](https://github.com/floci-io/floci) with a few changes that are not (yet) upstream. Nothing here is
sent upstream.

## Branches

| Ref | Meaning |
|---|---|
| `main` | Mirror of upstream `main`. Fast-forward only; nothing of ours is committed here. |
| `local` | `main` plus our changes, kept as a short, linear series of commits on top of `main`. Rebased onto `main` whenever upstream moves, so it is force-pushed. Builds and passes the tests below before every push. |
| `feat/*`, `fix/*` | Working branches off `main`; merged into `local` by cherry-pick. |

Update routine:

```bash
git fetch upstream
git checkout main && git merge --ff-only upstream/main
git checkout local && git rebase main      # stop and analyse when a commit conflicts or an upstream change overlaps ours
./mvnw test -Dtest='Bedrock*,Rds*,*Rds*,ContainerLauncher*,Lambda*,DockerHostResolverTest'   # JDK 25
git push --force-with-lease origin main local
```

A conflict is not resolved by habit: first check whether upstream has implemented the same thing (then our commit is dropped) or
changed the code our commit relies on (then it is adapted). Nothing is ever pushed to the `upstream` remote.

## What the fork changes (RDS and Lambda)

- **Per-instance database image.** `FLOCI_SERVICES_RDS_IMAGE_PROFILES=pgvector=pgvector/pgvector:pg16,...` names images;
  `--tags Key=floci:image-profile,Value=pgvector` on `create-db-instance` picks one for that instance (kept across reboot,
  snapshots, restores, replicas; unknown names are refused). See `docs/services/rds.md`, "Per-instance images".
- **PostgreSQL create without `docker exec`** (`FLOCI_SERVICES_RDS_INIT_WITHOUT_EXEC`, default on): create, describe and delete
  work behind a Docker socket proxy with `EXEC=0`.
- **Per-service Docker networks** (upstream): `FLOCI_SERVICES_RDS_DOCKER_NETWORK` and `FLOCI_SERVICES_LAMBDA_DOCKER_NETWORK`
  override `FLOCI_SERVICES_DOCKER_NETWORK`, so database containers can be reachable by apps while function containers are not.
- **Lambda**: the Runtime API address is Floci's address on the configured Lambda network (not its first network). Orphaned
  function containers are removed by upstream's container reconciler (`FLOCI_SERVICES_LAMBDA_RECONCILE_CONTAINERS_ON_STARTUP`).

## What the fork changes (Bedrock)

All in the Bedrock proxy backend (`floci.services.bedrock-runtime.backend=proxy`); see `docs/services/bedrock-runtime.md`.

- Converse and ConverseStream `document` blocks reach the OpenAI-compatible backend (txt, md, csv as text, html reduced to
  text, pdf as an OpenAI `file` part). doc, docx, xls, xlsx, S3 sources and unknown content blocks fail with
  `ValidationException` instead of being dropped.
- `additionalModelRequestFields` is merged into the backend request; `guardrailConfig`, `performanceConfig`,
  `requestMetadata`, `promptVariables` are accepted and logged as not applied; `cachePoint` blocks are ignored.
- `InvokeModel` for Titan and Cohere embedding models is forwarded to the backend's `/embeddings`.
- Documentation corrections (service matrix streaming note, InvokeModel in proxy mode, `CancelMessageMoveTask`).

`ListFoundationModels`, `GetFoundationModel` and the inference profile operations are upstream's (#7675, a static catalogue in
`bedrock/model-catalog.yaml`). A model that is only in the proxy model mapping is callable with Converse but is not listed.

## Build the image

The native image is the one to run (about 1 s to healthy, about 30 MB of RAM idle). Build it with the upstream
`docker/Dockerfile.native`, unchanged:

```bash
docker build -f docker/Dockerfile.native --build-arg VERSION=local -t floci-local:local .
```

A JVM-mode image is the alternative: `docker/Dockerfile.mvnw` (the upstream `docker/Dockerfile` with the pinned Maven wrapper).
About 6 minutes to build, but 1.15 GB on disk and about 440 MB of RAM idle. Pin its bases with
`--build-arg JDK_IMAGE=...@sha256:...`. Images are not published to any registry.

## Run the tests

JDK 25 is required (`./mvnw` refuses anything else). Without a local JDK 25:

```bash
docker run --rm -v "$PWD":/src -w /src eclipse-temurin:25-jdk-noble   bash -c 'apt-get update -qq && apt-get install -y -qq unzip >/dev/null && ./mvnw -B test -Dtest="Bedrock*,Rds*,*Rds*,ContainerLauncher*,Lambda*,DockerHostResolverTest"'
```
