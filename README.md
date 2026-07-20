# BaziKhooneh

BaziKhooneh is an accessible multiplayer game hub designed so blind,
low-vision, and sighted players can play familiar games together.

> **Status:** Early development. The first game will be three-player
> Tic-Tac-Toe.

## Vision

- Accessibility is part of the game design from the beginning.
- Every essential action and game-state change has a non-visual equivalent.
- Blind and sighted players use the same game rooms and follow the same rules.
- The app can grow into a collection of familiar multiplayer games.

## First milestone: three-player Tic-Tac-Toe

The first playable milestone will include:

- a three-player turn-based match;
- clear spoken and visual announcements for turns and moves;
- screen-reader-friendly controls and board navigation;
- high-contrast visuals that do not rely on color alone;
- accessible win, draw, error, and connection-status feedback.

Exact board size and winning rules will be documented before the game logic is
implemented.

## Accessibility principles

- Meaningful labels and predictable focus order
- Full TalkBack support
- Text and audio feedback for all important events
- Large touch targets and scalable text
- No information conveyed by color, position, or sound alone
- Accessibility testing included in the definition of done

## Platform

The current project is an Android application built with Gradle.

## Development

```bash
./gradlew test
./gradlew assembleDebug
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

## Roadmap

1. Define the rules and accessible interaction model for three-player Tic-Tac-Toe.
2. Build and test the local game engine.
3. Create the accessible Android interface.
4. Add multiplayer rooms and synchronization.
5. Add more games through a reusable game-module architecture.

## Contributing

The project is at an early stage. Contribution guidelines and the license will
be added before accepting external contributions.
