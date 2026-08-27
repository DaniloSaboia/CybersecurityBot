# Sentinel KMS — API contract

The wire format shared by the backend and the React app: answer blocks,
inline citations, the SSE stream, and the conversation memory endpoints.

All endpoints live under `/api` and require a Supabase access token
(`Authorization: Bearer <jwt>`). JWTs must carry the `authenticated`
audience claim (configurable / disableable via `SUPABASE_JWT_AUDIENCE`).

---

## 1. Chat answers — blocks

`POST /api/assistant/chat` returns:

```json
{
  "conversationId": "b8f6…",
  "blocks": [ … ],
  "citations": ["TI-2291", "CVE-2024-21413"]
}
```

`blocks` is an ordered array; each element is exactly one of:

| type | fields | rendering |
|---|---|---|
| `text` | `text` | paragraph; supports `**bold**` and `[ARTICLE-ID]` chips |
| `steps` | `items: string[]` | numbered procedure list |
| `callout` | `tone` (`critical`\|`high`), `title`, `text` | alert banner |
| `report` | `title`, `rows: [[label, value], …]` | key/value table |

`citations` lists the KB article ids the answer is grounded in. Inline
`[ARTICLE-ID]` tokens (e.g. `[TI-2291]`, `[CVE-2024-21413]`) may appear
inside `text` / `steps` and render as clickable chips; the backend filters
both the array and the inline tokens to ids that actually exist in
retrieval, so the model cannot invent citations.

## 2. Streaming — SSE

`POST /api/assistant/chat/stream` (accept: `text/event-stream`) emits:

```
data: {"type":"delta","text":"Revoke"}
data: {"type":"delta","text":" active sessions"}
data: {"type":"done","conversationId":"b8f6…","messageId":"3af1…","citations":["TI-2291"]}
```

- `delta` — one text chunk; concatenate in order for the live answer.
- `done` — terminal event: the conversation to continue with, the persisted
  assistant-message id (for feedback), and the grounded citations.

Frames follow standard SSE framing (`data: <json>\n\n`). Failures degrade
to an apologetic delta + a `done` event with empty citations — the stream
itself never errors.

## 3. Conversations (memory)

Threads are per-user (JWT `sub`); both chat endpoints accept an optional
`conversationId` to continue a thread and persist every turn (the recent
transcript is replayed into the prompt for multi-turn answers).

| endpoint | shape |
|---|---|
| `GET /api/assistant/conversations` | `[{ id, title, updatedAt }]`, newest first |
| `GET /api/assistant/conversations/{id}/messages` | `[{ id, role, content, citations, rating, createdAt }]`, oldest first |
| `DELETE /api/assistant/conversations/{id}` | 204; messages cascade |
| `POST /api/assistant/messages/{id}/feedback` | `{ rating: 1 \| -1 \| 0 }` → the updated message |

`rating` is analyst feedback: `1` helpful, `-1` not helpful, `0` clears it.
Only assistant messages in threads owned by the caller can be rated.

## 4. Retrieval — hybrid + IOC grounding

Answers are grounded by two fused retrieval legs (reciprocal-rank fusion):

1. **Vector** — pgvector cosine similarity over embedded chunks.
2. **Lexical** — Postgres full-text (`content_tsv`, `'simple'` config so
   exact tokens like `CVE-2024-21413` or hashes survive intact).

Additionally, indicators (IPv4, domain, sha256/sha1/md5, email) in the
question are looked up in the KB's IOC index (`articles.iocs` jsonb); any
article that lists them enters the context as an explicit
`IOC INDEX MATCH` source, so "have we seen 1.2.3.4?" answers from the
indicator index even when embedding similarity is low.
