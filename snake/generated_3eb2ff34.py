import pygame
import sys
import random

# Initialize Pygame
pygame.init()

# Constants
WIDTH, HEIGHT = 800, 600
BLOCK_SIZE = 20
SPEED = 10

# Colors (R, G, B)
BLACK = (0, 0, 0)
WHITE = (255, 255, 255)
RED = (255, 0, 0)
GREEN = (0, 255, 0)
BLUE = (0, 0, 255)

# Set up display
screen = pygame.display.set_mode((WIDTH, HEIGHT))
pygame.display.set_caption("Snake Game")

clock = pygame.time.Clock()
font = pygame.font.SysFont(None, 35)

def draw_snake(snake_body):
    for segment in snake_body:
        pygame.draw.rect(screen, GREEN, (segment[0], segment[1], BLOCK_SIZE, BLOCK_SIZE))

def draw_food(food_pos):
    pygame.draw.rect(screen, RED, (food_pos[0], food_pos[1], BLOCK_SIZE, BLOCK_SIZE))

def draw_score(score):
    score_text = font.render(f"Score: {score}", True, WHITE)
    screen.blit(score_text, (10, 10))

def display_game_over():
    game_over_text = font.render("Game Over! Press Q to Quit or R to Restart", True, RED)
    screen.blit(game_over_text, (WIDTH//2 - 250, HEIGHT//2))
    pygame.display.update()

def generate_food(snake_body):
    while True:
        x = random.randrange(0, WIDTH - BLOCK_SIZE, BLOCK_SIZE)
        y = random.randrange(0, HEIGHT - BLOCK_SIZE, BLOCK_SIZE)
        if (x, y) not in snake_body:
            return [x, y]

def main():
    # Initial snake setup
    snake_body = [[WIDTH//2, HEIGHT//2]]
    snake_direction = "RIGHT"
    change_to = snake_direction
    food_pos = generate_food(snake_body)
    score = 0
    game_over = False

    while not game_over:
        for event in pygame.event.get():
            if event.type == pygame.QUIT:
                pygame.quit()
                sys.exit()
            if event.type == pygame.KEYDOWN:
                if event.key == pygame.K_UP and snake_direction != "DOWN":
                    change_to = "UP"
                elif event.key == pygame.K_DOWN and snake_direction != "UP":
                    change_to = "DOWN"
                elif event.key == pygame.K_LEFT and snake_direction != "RIGHT":
                    change_to = "LEFT"
                elif event.key == pygame.K_RIGHT and snake_direction != "LEFT":
                    change_to = "RIGHT"
                elif event.key == pygame.K_q:
                    pygame.quit()
                    sys.exit()

        # Update direction
        snake_direction = change_to

        # Move snake head
        head_x, head_y = snake_body[0]
        if snake_direction == "UP":
            head_y -= BLOCK_SIZE
        elif snake_direction == "DOWN":
            head_y += BLOCK_SIZE
        elif snake_direction == "LEFT":
            head_x -= BLOCK_SIZE
        elif snake_direction == "RIGHT":
            head_x += BLOCK_SIZE

        # Insert new head
        new_head = [head_x, head_y]
        snake_body.insert(0, new_head)

        # Check collision with food
        if head_x == food_pos[0] and head_y == food_pos[1]:
            score += 1
            food_pos = generate_food(snake_body)
        else:
            snake_body.pop()

        # Check collision with walls
        if head_x < 0 or head_x >= WIDTH or head_y < 0 or head_y >= HEIGHT:
            game_over = True

        # Check collision with itself
        for segment in snake_body[1:]:
            if head_x == segment[0] and head_y == segment[1]:
                game_over = True

        # Drawing
        screen.fill(BLACK)
        draw_snake(snake_body)
        draw_food(food_pos)
        draw_score(score)
        pygame.display.update()

        clock.tick(SPEED)

    # Game over handling
    display_game_over()
    waiting_for_input = True
    while waiting_for_input:
        for event in pygame.event.get():
            if event.type == pygame.QUIT:
                pygame.quit()
                sys.exit()
            if event.type == pygame.KEYDOWN:
                if event.key == pygame.K_q:
                    pygame.quit()
                    sys.exit()
                elif event.key == pygame.K_r:
                    waiting_for_input = Fa