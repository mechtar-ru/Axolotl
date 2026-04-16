import { app, BrowserWindow, Tray, Menu, nativeImage, ipcMain, dialog, shell, globalShortcut, Notification } from 'electron';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn, ChildProcess } from 'node:child_process';
import https from 'node:https';
import http from 'node:http';
import { existsSync } from 'node:fs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const isDev = process.env.NODE_ENV === 'development' || !app.isPackaged;

let mainWindow: BrowserWindow | null = null;
let tray: Tray | null = null;
let backendProcess: ChildProcess | null = null;

const DIST = path.join(__dirname, '../dist');
const VITE_DEV_SERVER_URL = process.env.VITE_DEV_SERVER_URL;
const BACKEND_PORT = 8080;
const BACKEND_URL = `http://localhost:${BACKEND_PORT}`;

async function waitForBackend(maxAttempts = 30): Promise<boolean> {
  for (let i = 0; i < maxAttempts; i++) {
    try {
      await new Promise<void>((resolve, reject) => {
        const req = http.get(`${BACKEND_URL}/api/health`, (res) => {
          if (res.statusCode === 200) {
            resolve();
          } else {
            res.resume(); // drain response
            reject(new Error(`Status: ${res.statusCode}`));
          }
        });
        req.on('error', reject);
        req.setTimeout(1000, () => { req.destroy(); reject(new Error('Timeout')); });
      });
      console.log('[Axolotl] Backend ready!');
      return true;
    } catch {
      await new Promise(r => setTimeout(r, 1000));
    }
  }
  return false;
}

function startBackend(): Promise<void> {
  return new Promise((resolve, reject) => {
    const jarPath = isDev
      ? path.join(__dirname, '../backend/target/axolotl-0.0.1.jar')
      : path.join(process.resourcesPath!, 'backend/axolotl-0.0.1.jar');

    console.log('[Axolotl] Starting backend from:', jarPath);

    let javaHome = process.env.JAVA_HOME;
    
    if (!isDev && !javaHome) {
      const bundledJre = path.join(process.resourcesPath!, 'jre');
      const bundledJreWithHome = path.join(bundledJre, 'Contents', 'Home');
      if (existsSync(path.join(bundledJreWithHome, 'bin', 'java'))) {
        javaHome = bundledJreWithHome;
      } else if (existsSync(path.join(bundledJre, 'bin', 'java'))) {
        javaHome = bundledJre;
      }
    }
    
    const javaExe = javaHome 
      ? path.join(javaHome, 'bin', 'java') 
      : 'java';

    console.log('[Axolotl] Using Java:', javaExe);

    const userDataPath = app.getPath('userData');
    console.log('[Axolotl] User data path:', userDataPath);

    backendProcess = spawn(javaExe, [
      '-jar', jarPath,
      `--server.port=${BACKEND_PORT}`
    ], {
      stdio: ['ignore', 'pipe', 'pipe'],
      detached: false,
      cwd: userDataPath
    });

    backendProcess.stdout?.on('data', (data) => {
      const line = data.toString().trim();
      if (line) console.log('[Backend]', line);
    });

    backendProcess.stderr?.on('data', (data) => {
      const line = data.toString().trim();
      if (line) console.error('[Backend Error]', line);
    });

    backendProcess.on('error', (err) => {
      console.error('[Axolotl] Failed to start backend:', err);
      reject(err);
    });

    waitForBackend().then((ready) => {
      if (ready) {
        resolve();
      } else {
        reject(new Error('Backend failed to start within timeout'));
      }
    });
  });
}

function stopBackend(): Promise<void> {
  if (!backendProcess) return Promise.resolve();
  const proc = backendProcess;
  backendProcess = null;
  console.log('[Axolotl] Stopping backend...');
  return new Promise((resolve) => {
    const timeout = setTimeout(() => {
      console.warn('[Axolotl] Backend did not exit in time, force killing');
      proc.kill('SIGKILL');
      resolve();
    }, 5000);
    proc.on('exit', () => {
      clearTimeout(timeout);
      resolve();
    });
    proc.kill('SIGTERM');
  });
}

function createTray() {
  const iconPath = path.join(__dirname, '../frontend/public/vite.svg');

  let icon: nativeImage;
  try {
    icon = nativeImage.createFromPath(iconPath);
    if (icon.isEmpty()) {
      icon = nativeImage.createEmpty();
    }
  } catch {
    icon = nativeImage.createEmpty();
  }

  tray = new Tray(icon.resize({ width: 16, height: 16 }));

  const contextMenu = Menu.buildFromTemplate([
    {
      label: 'Show Axolotl',
      click: () => mainWindow?.show()
    },
    {
      label: 'New Workflow',
      click: () => {
        mainWindow?.show();
        mainWindow?.webContents.send('create-new-workflow');
      }
    },
    { type: 'separator' },
    {
      label: 'Quit',
      click: () => {
        app.quit();
      }
    }
  ]);

  tray.setToolTip('Axolotl — Visual AI Orchestration');
  tray.setContextMenu(contextMenu);

  tray.on('click', () => {
    mainWindow?.show();
  });
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1400,
    height: 900,
    minWidth: 1024,
    minHeight: 700,
    title: 'Axolotl',
    icon: path.join(__dirname, '../frontend/public/vite.svg'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.mjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false
    },
    show: false
  });

  mainWindow.once('ready-to-show', () => {
    mainWindow?.show();
  });

  mainWindow.on('close', (event) => {
    if (process.platform === 'darwin') {
      // macOS: hide window, keep app running
      event.preventDefault();
      mainWindow?.hide();
    }
    // Windows/Linux: allow close, app will quit via window-all-closed
  });

  mainWindow.on('closed', () => {
    mainWindow = null;
  });

  if (VITE_DEV_SERVER_URL) {
    mainWindow.loadURL(VITE_DEV_SERVER_URL);
    mainWindow.webContents.openDevTools();
  } else {
    mainWindow.loadFile(path.join(DIST, 'index.html'));
  }
}

function createMenu() {
  const template: Electron.MenuItemConstructorOptions[] = [
    {
      label: 'File',
      submenu: [
        {
          label: 'New Workflow',
          accelerator: 'CmdOrCtrl+N',
          click: () => mainWindow?.webContents.send('create-new-workflow')
        },
        {
          label: 'Open...',
          accelerator: 'CmdOrCtrl+O',
          click: () => mainWindow?.webContents.send('open-workflow')
        },
        {
          label: 'Save',
          accelerator: 'CmdOrCtrl+S',
          click: () => mainWindow?.webContents.send('save-workflow')
        },
        { type: 'separator' },
        {
          label: 'Export as PNG',
          click: () => mainWindow?.webContents.send('export-png')
        },
        {
          label: 'Export as JSON',
          click: () => mainWindow?.webContents.send('export-json')
        },
        { type: 'separator' },
        { role: 'quit' }
      ]
    },
    {
      label: 'Edit',
      submenu: [
        { role: 'undo' },
        { role: 'redo' },
        { type: 'separator' },
        { role: 'cut' },
        { role: 'copy' },
        { role: 'paste' },
        { role: 'selectAll' }
      ]
    },
    {
      label: 'View',
      submenu: [
        { role: 'reload' },
        { role: 'forceReload' },
        { role: 'toggleDevTools' },
        { type: 'separator' },
        { role: 'resetZoom' },
        { role: 'zoomIn' },
        { role: 'zoomOut' },
        { type: 'separator' },
        { role: 'togglefullscreen' }
      ]
    },
    {
      label: 'Window',
      submenu: [
        { role: 'minimize' },
        { role: 'zoom' },
        { role: 'close' }
      ]
    },
    {
      label: 'Help',
      submenu: [
        {
          label: 'Documentation',
          click: () => shell.openExternal('https://github.com/anomalyco/axolotl')
        },
        {
          label: 'Report Issue',
          click: () => shell.openExternal('https://github.com/anomalyco/axolotl/issues')
        },
        { type: 'separator' },
        {
          label: 'About Axolotl',
          click: () => {
            dialog.showMessageBox(mainWindow!, {
              type: 'info',
              title: 'About Axolotl',
              message: 'Axolotl',
              detail: 'Visual AI-Agent Orchestration\nVersion ' + app.getVersion()
            });
          }
        }
      ]
    }
  ];

  if (process.platform === 'darwin') {
    template.unshift({
      label: app.name,
      submenu: [
        { role: 'about' },
        { type: 'separator' },
        { role: 'services' },
        { type: 'separator' },
        { role: 'hide' },
        { role: 'hideOthers' },
        { role: 'unhide' },
        { type: 'separator' },
        { role: 'quit' }
      ]
    });
  }

  const menu = Menu.buildFromTemplate(template);
  Menu.setApplicationMenu(menu);
}

function registerShortcuts() {
  globalShortcut.register('CmdOrCtrl+Shift+A', () => {
    if (mainWindow?.isVisible()) {
      mainWindow.hide();
    } else {
      mainWindow?.show();
      mainWindow?.focus();
    }
  });
}

async function setupAutoUpdater() {
  if (isDev) return;

  try {
    const { autoUpdater } = await import('electron-updater');
    autoUpdater.logger = (await import('electron-log')).default;

    autoUpdater.on('checking-for-update', () => {
      mainWindow?.webContents.send('update-status', { status: 'checking' });
    });

    autoUpdater.on('update-available', (info) => {
      mainWindow?.webContents.send('update-status', { status: 'available', info });
    });

    autoUpdater.on('update-not-available', () => {
      mainWindow?.webContents.send('update-status', { status: 'not-available' });
    });

    autoUpdater.on('download-progress', (progress) => {
      mainWindow?.webContents.send('update-status', { status: 'downloading', progress });
    });

    autoUpdater.on('update-downloaded', (info) => {
      mainWindow?.webContents.send('update-status', { status: 'downloaded', info });
      dialog.showMessageBox(mainWindow!, {
        type: 'info',
        title: 'Update Ready',
        message: 'A new version has been downloaded. Restart to apply the update?',
        buttons: ['Restart', 'Later']
      }).then((result) => {
        if (result.response === 0) {
          autoUpdater.quitAndInstall();
        }
      });
    });

    autoUpdater.on('error', (err) => {
      mainWindow?.webContents.send('update-status', { status: 'error', error: err.message });
    });

    await autoUpdater.checkForUpdatesAndNotify();
  } catch (error) {
    console.error('Auto-updater error:', error);
  }
}

app.whenReady().then(async () => {
  try {
    if (!VITE_DEV_SERVER_URL) {
      console.log('[Axolotl] Starting backend server...');
      await startBackend();
    }
    createWindow();
    createTray();
    createMenu();
    registerShortcuts();
    await setupAutoUpdater();

    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) {
        createWindow();
      } else {
        mainWindow?.show();
      }
    });
  } catch (err) {
    console.error('[Axolotl] Failed to start:', err);
    dialog.showErrorBox('Startup Error', `Failed to start Axolotl: ${err}`);
    app.quit();
  }
});

app.on('window-all-closed', () => {
  stopBackend();
  if (process.platform !== 'darwin') {
    app.quit();
  }
});

app.on('will-quit', async () => {
  await stopBackend();
  globalShortcut.unregisterAll();
});

ipcMain.handle('show-notification', async (_, { title, body }) => {
  if (Notification.isSupported()) {
    new Notification({ title, body }).show();
  }
  return true;
});

ipcMain.handle('get-app-version', () => {
  return app.getVersion();
});

ipcMain.handle('get-app-path', () => {
  return app.getPath('userData');
});

ipcMain.handle('show-save-dialog', async (_, options) => {
  return dialog.showSaveDialog(mainWindow!, options);
});

ipcMain.handle('show-open-dialog', async (_, options) => {
  return dialog.showOpenDialog(mainWindow!, options);
});

ipcMain.handle('read-file', async (_, filePath) => {
  const udPath = app.getPath('userData');
  const resolved = path.resolve(filePath);
  if (!resolved.startsWith(udPath)) {
    throw new Error('Access denied: path outside userData');
  }
  const fs = await import('fs/promises');
  return fs.readFile(resolved, 'utf-8');
});

ipcMain.handle('write-file', async (_, { filePath, content }) => {
  const udPath = app.getPath('userData');
  const resolved = path.resolve(filePath);
  if (!resolved.startsWith(udPath)) {
    throw new Error('Access denied: path outside userData. Use showSaveDialog first.');
  }
  const fs = await import('fs/promises');
  await fs.writeFile(resolved, content, 'utf-8');
  return true;
});

ipcMain.handle('open-external', async (_, url) => {
  return shell.openExternal(url);
});

ipcMain.handle('window-minimize', () => {
  mainWindow?.minimize();
});

ipcMain.handle('window-maximize', () => {
  if (mainWindow?.isMaximized()) {
    mainWindow.unmaximize();
  } else {
    mainWindow?.maximize();
  }
});

ipcMain.handle('window-close', () => {
  mainWindow?.close();
});

ipcMain.handle('window-is-maximized', () => {
  return mainWindow?.isMaximized() ?? false;
});

ipcMain.handle('check-for-updates', async () => {
  if (isDev) return { available: false };
  try {
    const { autoUpdater } = await import('electron-updater');
    return await autoUpdater.checkForUpdates();
  } catch {
    return { available: false };
  }
});
