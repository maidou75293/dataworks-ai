# DataWorks-AI

**An open-source, self-hosted data platform** — DataWorks-style data integration, development, scheduling, quality, service & BI, plus a built-in conversational **agent service** — that runs entirely on your own hardware. One `docker compose up` gets you a working demo; an Ansible + k3s path takes it to production.

[中文 README](README-zh.md) · [Apache-2.0](LICENSE)

![Java](https://img.shields.io/badge/backend-Spring%20Boot-orange) ![React](https://img.shields.io/badge/frontend-React%20%2B%20TS-blue) ![Deploy](https://img.shields.io/badge/deploy-docker%20compose%20%7C%20Ansible%20%2B%20k3s-green)

---

## Why this exists

Most teams that outgrow "cron + scripts" face the same menu: a cloud data platform (DataWorks / DataProc / …) that locks data and pipelines into one vendor, or hand-assembling 6 OSS components (Airflow + dbt + DolphinScheduler + Superset + Ranger + …) and becoming their SRE team.

DataWorks-AI is **one product covering the workflow** — sources → integration → dev/IDE → scheduling → quality → publish as API/BI → governance/audit → ops — with a **conversational agent on top** that can browse metadata and develop data tasks for you. Private by default: every credential is AES-256-GCM encrypted at rest, nothing phones home, and the whole stack is plain Docker/K8s you can air-gap.

## 30-second demo

The only prerequisite is **Docker** (compose v2.24+). Java/Node are compiled inside containers — your host needs neither.

```bash
git clone https://github.com/maidou75293/dataworks-ai.git
cd dataworks-ai/deploy/compose
bash up.sh
```

`up.sh` generates local-randomized credentials, builds the images, brings up the stack (web, backend, PostgreSQL, Keycloak SSO, SeaweedFS S3, Apache Doris), waits for health, and prints a **one-time admin password**. Open `http://localhost:30090`. Tear down with `bash down.sh`.

> First build pulls Maven/npm deps: 10–20 min. Cached afterwards it starts in seconds.
> Running `docker compose up` directly skips `up.sh` and uses public `demo-*` fallback credentials — **localhost viewing only, never expose those ports**.

## What you get

| Domain | Capabilities |
|---|---|
| **Data integration** | Batch sync tasks (SeaTunnel-family), heterogeneous sources, one-off doris/postgres business accounts provisioned automatically |
| **Data development** | SQL IDE with Flyway-migrated metadata, workflow DAG editor, UDF/function management, stream development (Flink on K8s) |
| **Scheduling & ops** | DAG scheduler (Prefect-backed), run instances, baselines, alert center (email/webhook with encrypted channel secrets), ops overview dashboards |
| **Data quality & governance** | Quality rules, SQL change requests with approval flow + backup plan enforcement, security center, permission-request workflow, metadata catalog & lineage, Gravitino care |
| **Data service & BI** | Publish datasets as APIs, built-in BI charts, multi-tenant workspaces |
| **Multimodal & model ops (MOS)** | Labeling projects, training sets, training jobs, model evaluation, model assets, data flywheel — a mini MLOps lane for AI training data |
| **Agent service** | OpenAI-compatible LLM endpoint (any vendor), conversational data development, config via UI **without restart**; secrets only ever shown as last-4 fingerprints |
| **Security** | Keycloak SSO + local auth, session service, AES-256-GCM secret store (`PLATFORM_SECRET_KEY` required — backend refuses to start without it), audited secret access |

Architecture at a glance: `React SPA (nginx) → Spring Boot API (34 modules) → PostgreSQL metadata / Doris warehouse / SeaweedFS S3`, optionally `→ Python agent-service → your LLM provider`. Kubernetes deployment ships as templated manifests (`deploy/k8s/`) driven by Ansible inventories.

## Repository layout

| Path | What's inside |
|---|---|
| `backend/` | Spring Boot service, Flyway migrations (`src/main/resources/db/migration`) |
| `web/` | React + TypeScript SPA |
| `agent-service/` | Python agent service (env-driven, hot-configurable) |
| `deploy/compose/` | Single-host demo stack (`up.sh` / `down.sh`) |
| `deploy/` | Production path: Ansible playbooks + k3s/K8s templates, all values in `*.example.*` |
| `mos/` | Multimodal-asset / labeling subsystem |
| `services/` `prompt-graph/` `e2e/` | Side services, prompt assets, end-to-end acceptance harness |

## Configuration model

Every knob follows **user-changeable, always-defaulted**: each component ships a `.env.example`; empty values fall back to safe local defaults. Real credentials live in gitignored `.env` / `keys/` / `secrets/` and are never echoed in plaintext by the UI. Production deployments resolve host-specific values from your own `env.local.sh` (see `deploy/env.local.sh.example`) — the repo contains zero internal IPs by design.

## Honest limitations

- The compose demo is a **single-node evaluation stack**; production scheduling/stream features target the K8s path.
- MOS/model-training lanes assume you bring your own GPU cluster; the platform orchestrates, it does not train.
- The agent service's quality depends on the LLM provider you point it at; the repo ships no model.
- This project was first published 2026-11-05; expect rough edges in docs before issues in code — bug reports with repro steps are the fastest fix path.

## Development

```bash
# backend (inside containers, or local if you have JDK 21 + Maven)
cd backend && ./mvnw spring-boot:run
# frontend
cd web && npm ci && npm run dev
```

CI gates under `.github/workflows/` run on every PR.

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
