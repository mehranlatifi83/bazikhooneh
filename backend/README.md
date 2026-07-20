# BaziKhooneh Backend

Authoritative multiplayer server for BaziKhooneh, built with Django and
Django Channels.

## Local setup

```powershell
cd backend
python -m venv .venv
.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
python manage.py migrate
python manage.py runserver
```

The development server starts at `http://127.0.0.1:8000`.

## HTTP API

- `GET /health/`
- `POST /api/v1/rooms/` creates a room and returns the X player's reconnect
  token.
- `POST /api/v1/rooms/join/` with `{ "code": "ABC123" }` joins the room as O
  and returns that player's reconnect token.

## WebSocket API

Connect to:

```text
/ws/v1/rooms/<code>/?token=<reconnect-token>
```

Placement action:

```json
{"type":"action","action_id":"client-generated-id","action":{"kind":"place","destination":4}}
```

Movement action:

```json
{"type":"action","action_id":"client-generated-id","action":{"kind":"move","source":0,"destination":4}}
```

The server validates every action and broadcasts the authoritative game state
to both players.

## Configuration

Development uses SQLite and an in-memory channel layer. Copy `.env.example`
values into your environment when needed. Production should set a strong
`DJANGO_SECRET_KEY`, disable debug mode, use PostgreSQL, and configure
`REDIS_URL` so WebSocket messages work across server processes.

Never commit environment files, secrets, or reconnect tokens.

## Tests

```powershell
python manage.py test
```
