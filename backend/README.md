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

- `POST /api/v1/accounts/register/` creates an account with a username,
  display name, and password.
- `POST /api/v1/accounts/login/` creates a revocable 30-day session.
- `GET` or `PATCH /api/v1/accounts/me/` reads or updates the profile.
- `POST /api/v1/accounts/logout/` revokes the current session.
- `POST /api/v1/accounts/username/` changes a username after password confirmation;
  changes have a 30-day cooldown and previous names are reserved for 90 days.
- `POST /api/v1/accounts/password/` changes the password and revokes other sessions.
- `POST /api/v1/accounts/email/request/` and `email/confirm/` verify an email address.
- `POST /api/v1/accounts/password-reset/request/` and `password-reset/confirm/`
  provide one-time password recovery without revealing whether an account exists.
- `GET` or `DELETE /api/v1/accounts/sessions/` lists sessions or revokes other devices.
- `GET` or `POST /api/v1/accounts/friends/` lists friends or sends a request.
- `POST /api/v1/accounts/friends/requests/<id>/accept/` accepts a friend request.
- `GET` or `POST /api/v1/accounts/invites/` lists invitations or creates a private
  Tic-Tac-Toe room for an accepted friend.
- `GET /health/`
- `POST /api/v1/rooms/` creates a room and returns the X player's reconnect
  token.
- `POST /api/v1/rooms/join/` with `{ "code": "ABC123" }` joins the room as O
  and returns that player's reconnect token.
- `GET /api/v1/matches/` returns up to 50 completed matches for the signed-in
  account.

Room and match endpoints require `Authorization: Bearer <account-token>`.
Local and bot games in the Android app do not require an account.

## WebSocket API

Connect to:

```text
/ws/v1/rooms/<code>/
```

Send the reconnect token in the WebSocket handshake as
`Authorization: Bearer <reconnect-token>`.

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

Request a rematch after a finished game with `{"type":"rematch"}`. The board
is reset only after both players request it. Presence messages report when a
player connects or disconnects.

Leave a room with `{"type":"leave"}`. The room is closed, the leaving
player's reconnect token is revoked, and the other player receives the updated
state.

## Configuration

Development uses SQLite and an in-memory channel layer. Copy `.env.example`
values into your environment when needed. Production should set a strong
`DJANGO_SECRET_KEY`, disable debug mode, use PostgreSQL, and configure
`REDIS_URL` so WebSocket messages work across server processes.
Configure `EMAIL_BACKEND` and `DEFAULT_FROM_EMAIL` for verification and password
recovery. Development defaults to Django's console email backend.

Never commit environment files, secrets, or reconnect tokens.

## Tests

```powershell
python manage.py test
```

Expired rooms can be removed by a scheduled maintenance job:

```powershell
python manage.py cleanup_rooms
```

Waiting rooms expire after two hours and closed or finished rooms after 24
hours. Both thresholds can be changed with command options.
