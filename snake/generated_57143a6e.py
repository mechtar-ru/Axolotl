def generate_food(snake_body):
    while True:
        x = random.randrange(0, WIDTH - BLOCK_SIZE, BLOCK_SIZE)
        y = random.randrange(0, HEIGHT - BLOCK_SIZE, BLOCK_SIZE)
        if (x, y) not in snake_body:
            return [x, y]