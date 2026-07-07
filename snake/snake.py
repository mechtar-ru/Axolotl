#!/usr/bin/env python3
"""Snake game built with Axolotl pipeline (Ollama qwen2.5-coder:14b)."""
import pygame
import sys
import random

pygame.init()

WIDTH, HEIGHT = 600, 600
BLOCK_SIZE = 20
SPEED = 10

BLACK = (0, 0, 0)
WHITE = (255, 255, 255)
RED = (255, 0, 0)
GREEN = (0, 255, 0)
BLUE = (0, 0, 0)

screen = pygame.display.set_mode((WIDTH, HEIGHT))
pygame.display.set_caption("Snake Game")
clock = pygame.time.Clock()
font = pygame.font.SysFont(None, 36)

def draw_snake(snake):
    for seg in snake:
        pygame.draw.rect(screen, GREEN, (seg[0], seg[1], BLOCK_SIZE, BLOCK_SIZE))

def draw_food(pos):
    pygame.draw.rect(screen, RED, (pos[0], pos[1], BLOCK_SIZE, BLOCK_SIZE))

def show_score(score):
    text = font.render(f"Score: {score}", True, WHITE)
    screen.blit(text, (10, 10))

def game_over_screen(score):
    screen.fill(BLACK)
    msg1 = font.render(f"Game Over! Score: {score}", True, RED)
    msg2 = font.render("Press R to restart or Q to quit", True, WHITE)
    screen.blit(msg1, (WIDTH // 2 - msg1.get_width() // 2, HEIGHT // 2 - 40))
    screen.blit(msg2, (WIDTH // 2 - msg2.get_width() // 2, HEIGHT // 2))
    pygame.display.update()
    while True:
        for event in pygame.event.get():
            if event.type == pygame.QUIT:
                pygame.quit(); sys.exit()
            if event.type == pygame.KEYDOWN:
                if event.key == pygame.K_q:
                    pygame.quit(); sys.exit()
                if event.key == pygame.K_r:
                    return

def game_loop():
    x, y = WIDTH // 2, HEIGHT // 2
    dx, dy = BLOCK_SIZE, 0
    snake = [[x, y]]
    food = [random.randrange(0, WIDTH, BLOCK_SIZE),
            random.randrange(0, HEIGHT, BLOCK_SIZE)]
    score = 0
    running = True

    while running:
        for event in pygame.event.get():
            if event.type == pygame.QUIT:
                pygame.quit(); sys.exit()
            if event.type == pygame.KEYDOWN:
                if event.key == pygame.K_LEFT and dx == 0:
                    dx, dy = -BLOCK_SIZE, 0
                elif event.key == pygame.K_RIGHT and dx == 0:
                    dx, dy = BLOCK_SIZE, 0
                elif event.key == pygame.K_UP and dy == 0:
                    dx, dy = 0, -BLOCK_SIZE
                elif event.key == pygame.K_DOWN and dy == 0:
                    dx, dy = 0, BLOCK_SIZE

        x += dx
        y += dy

        # Wall collision
        if x < 0 or x >= WIDTH or y < 0 or y >= HEIGHT:
            game_over_screen(score)
            return

        head = [x, y]
        snake.append(head)

        # Self collision
        if head in snake[:-1]:
            game_over_screen(score)
            return

        # Food collision
        if x == food[0] and y == food[1]:
            score += 1
            food = [random.randrange(0, WIDTH, BLOCK_SIZE),
                    random.randrange(0, HEIGHT, BLOCK_SIZE)]
        else:
            snake.pop(0)

        screen.fill(BLACK)
        draw_food(food)
        draw_snake(snake)
        show_score(score)
        pygame.display.update()
        clock.tick(SPEED)

while True:
    game_loop()
