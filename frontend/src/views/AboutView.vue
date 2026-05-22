<template>
  <div class="about-page">
    <div class="about-content">
      <h1>Axolotl</h1>
      <p class="subtitle">Visual AI Agent Builder</p>
      <div class="info-card">
        <h2>Version</h2>
        <p class="version">1.0.0</p>
      </div>
      <div class="info-card">
        <h2>Resources</h2>
        <a href="https://mechtar-ru.github.io/Axolotl/" target="_blank" rel="noopener">Documentation →</a>
        <a href="https://github.com/mechtar-ru/Axolotl" target="_blank" rel="noopener">GitHub →</a>
      </div>
    </div>
  </div>
</template>

<style scoped>
.about-page {
  min-height: 100vh;
  display: flex;
  justify-content: center;
  align-items: center;
  background: var(--bg-primary);
}
.about-content {
  max-width: 480px;
  text-align: center;
}
.about-content h1 {
  font-size: var(--text-3xl);
  color: var(--text-primary);
  margin: 0;
}
.subtitle {
  color: var(--text-secondary);
  margin: var(--space-2) 0 var(--space-6);
}
.info-card {
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  padding: var(--space-4);
  margin-bottom: var(--space-3);
  text-align: left;
}
.info-card h2 {
  font-size: var(--text-sm);
  color: var(--text-muted);
  margin: 0 0 var(--space-1);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.version {
  font-family: var(--font-mono);
  color: var(--text-primary);
  margin: 0;
}
.info-card a {
  display: block;
  color: var(--accent);
  text-decoration: none;
  padding: var(--space-1) 0;
  font-size: var(--text-sm);
}
.info-card a:hover {
  color: var(--accent-hover);
}
</style>
