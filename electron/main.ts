import { app, BrowserWindow, Tray, Menu, nativeImage, ipcMain, dialog, shell, globalShortcut, Notification } from 'electron';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const isDev = process.env.NODE_ENV === 'development' || !app.isPackaged;

let mainWindow: BrowserWindow | null = null;
let tray: Tray | null = null;

const DIST = path.join(__dirname, '../dist');
const VITE_DEV_SERVER_URL = process.env.VITE_DEV_SERVER_URL;

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
    if (process.platform !== 'darwin') {
      event.preventDefault();
      mainWindow?.hide();
    }
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
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});

app.on('will-quit', () => {
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
  const fs = await import('fs/promises');
  return fs.readFile(filePath, 'utf-8');
});

ipcMain.handle('write-file', async (_, { filePath, content }) => {
  const fs = await import('fs/promises');
  await fs.writeFile(filePath, content, 'utf-8');
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
