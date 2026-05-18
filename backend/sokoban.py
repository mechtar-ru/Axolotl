#!/usr/bin/env python3
"""
Sokoban Game
Controls: W/A/S/D to move, U to undo, R to reset, Q to quit.
"""

import os
import sys


class Sokoban:
    """Main game class."""

    levels = [
        # Level 1
        [
            "##########",
            "#        #",
            "#  .     #",
            "#  $     #",
            "#  @     #",
            "#        #",
            "#        #",
            "#        #",
            "#        #",
            "##########",
        ],
        # Level 2
        [
            "##########",
            "#  #     #",
            "#  . $   #",
            "#  $ .   #",
            "#   #    #",
            "#        #",
            "#        #",
            "#      @ #",
            "#        #",
            "##########",
        ],
        # Level 3
        [
            "##########",
            "#  .  .  #",
            "# $   $  #",
            "#   .    #",
            "#    #   #",
            "#   #    #",
            "#  #     #",
            "# @      #",
            "#   $    #",
            "##########",
        ],
    ]

    def __init__(self):
        self.current_level = 0
        self.reset_level()

    def reset_level(self):
        """Parse the current level and set the initial state."""
        level = self.levels[self.current_level]
        self.height = len(level)
        self.width = len(level[0])
        self.walls = set()
        self.boxes = set()
        self.targets = set()
        self.player_pos = None
        self.history = []
        self.moves = 0

        for r, row in enumerate(level):
            for c, ch in enumerate(row):
                if ch == '#':
                    self.walls.add((r, c))
                elif ch == '$':
                    self.boxes.add((r, c))
                elif ch == '.':
                    self.targets.add((r, c))
                elif ch == '@':
                    self.player_pos = (r, c)
                elif ch == '+':  # player on target
                    self.player_pos = (r, c)
                    self.targets.add((r, c))
                elif ch == '*':  # box on target
                    self.boxes.add((r, c))
                    self.targets.add((r, c))

    def display(self):
        """Render the current board to stdout."""
        # Clear screen (works on both Windows and Unix-like)
        os.system('cls' if os.name == 'nt' else 'clear')
        print(f"Level {self.current_level + 1} | Moves: {self.moves}\n")
        for r in range(self.height):
            row = []
            for c in range(self.width):
                if (r, c) in self.walls:
                    row.append('#')
                elif (r, c) == self.player_pos:
                    row.append('@')
                elif (r, c) in self.boxes:
                    if (r, c) in self.targets:
                        row.append('*')  # box on target
                    else:
                        row.append('O')  # box
                elif (r, c) in self.targets:
                    row.append('.')  # target
                else:
                    row.append(' ')
            print(''.join(row))
        print()

    def move(self, direction):
        """Attempt to move the player in direction (dr, dc). Returns True if move succeeded."""
        dr, dc = direction
        r, c = self.player_pos
        nr, nc = r + dr, c + dc

        # Cannot walk through walls
        if (nr, nc) in self.walls:
            return False

        # If a box is present, try to push it
        if (nr, nc) in self.boxes:
            nnr, nnc = nr + dr, nc + dc
            # Blocked by wall or another box
            if (nnr, nnc) in self.walls or (nnr, nnc) in self.boxes:
                return False
            # Save state for undo
            self.history.append((self.player_pos, set(self.boxes)))
            self.boxes.remove((nr, nc))
            self.boxes.add((nnr, nnc))
            self.player_pos = (nr, nc)
            self.moves += 1
            return True

        # Empty cell, just walk
        if (nr, nc) not in self.walls and (nr, nc) not in self.boxes:
            self.history.append((self.player_pos, set(self.boxes)))
            self.player_pos = (nr, nc)
            self.moves += 1
            return True

        return False

    def undo(self):
        """Undo the last move."""
        if self.history:
            self.player_pos, self.boxes = self.history.pop()
            self.moves -= 1
            return True
        return False

    def reset(self):
        """Reset the current level to its initial state."""
        self.reset_level()

    def check_victory(self):
        """Return True if every target has a box on it."""
        return self.targets.issubset(self.boxes)

    def next_level(self):
        """Advance to the next level if available."""
        if self.current_level < len(self.levels) - 1:
            self.current_level += 1
            self.reset_level()
            return True
        return False


def main():
    game = Sokoban()
    direction_keys = {
        'w': (-1, 0),
        'a': (0, -1),
        's': (1, 0),
        'd': (0, 1),
    }

    while True:
        game.display()
        if game.check_victory():
            print("🎉 Congratulations! Level complete!")
            if game.next_level():
                print("Loading next level...")
                continue
            else:
                print("You beat all levels! Well done!")
                sys.exit(0)

        cmd = input("Move (W/A/S/D), Undo (U), Reset (R), Quit (Q): ").strip().lower()
        if not cmd:
            continue

        if cmd in direction_keys:
            game.move(direction_keys[cmd])
        elif cmd == 'u':
            if not game.undo():
                print("Nothing to undo.")
                input("Press Enter...")
        elif cmd == 'r':
            game.reset()
        elif cmd == 'q':
            print("Goodbye!")
            sys.exit(0)
        else:
            print("Invalid input.")
            input("Press Enter...")


if __name__ == "__main__":
    main()
