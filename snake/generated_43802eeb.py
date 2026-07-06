while not game_over:
    for event in pygame.event.get():
        ...
    # Update direction
    snake_direction = change_to
    
    # Move snake head
    head_x, head_y = snake_body[0]
    ...
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