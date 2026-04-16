import https from 'node:https';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createWriteStream, mkdirSync, existsSync } from 'node:fs';
import { pipeline } from 'node:stream/promises';
import { execSync } from 'node:child_process';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const JRE_VERSION = '21.0.2';
const JRE_DIR = path.join(__dirname, '../jre');

// Cross-platform JRE URL
const platform = process.platform === 'darwin' ? 'mac'
  : process.platform === 'win32' ? 'windows'
  : 'linux';
const arch = process.arch === 'arm64' ? 'aarch64' : 'x64';
const ext = platform === 'windows' ? 'zip' : 'tar.gz';

const JRE_URL = `https://github.com/adoptium/temurin21-binaries/releases/download/jdk-${JRE_VERSION}%2B8/OpenJDK21U-jre_${arch}_${platform}_hotspot_${JRE_VERSION}_8.${ext}`;
const JRE_ARCHIVE = path.join(JRE_DIR, `jre.${ext}`);

async function download(url, dest) {
  return new Promise((resolve, reject) => {
    const file = createWriteStream(dest);
    const protocol = url.startsWith('https') ? https : http;

    console.log(`Downloading JRE from ${url}...`);

    const maxRedirects = 5;
    function follow(currentUrl, redirectsLeft) {
      if (redirectsLeft <= 0) return reject(new Error('Too many redirects'));
      const p = currentUrl.startsWith('https') ? https : http;
      p.get(currentUrl, (response) => {
        if (response.statusCode === 301 || response.statusCode === 302) {
          const location = response.headers.location;
          if (!location || (!location.startsWith('https://') && !location.startsWith('http://'))) {
            return reject(new Error(`Invalid redirect: ${location}`));
          }
          response.resume();
          follow(location, redirectsLeft - 1);
          return;
        }
        if (response.statusCode !== 200) {
          response.resume();
          return reject(new Error(`HTTP ${response.statusCode}`));
        }
        pipeline(response, file).then(resolve).catch(reject);
      }).on('error', reject);
    }
    follow(url, maxRedirects);
  });
}

async function extractJre(archive, dest) {
  console.log('Extracting JRE...');
  mkdirSync(dest, { recursive: true });

  if (archive.endsWith('.zip')) {
    // Windows: use PowerShell or unzip
    try {
      execSync(`powershell -command "Expand-Archive -Path '${archive}' -DestinationPath '${dest}'"`, { stdio: 'inherit' });
    } catch {
      // Fallback: try unzip command
      execSync(`unzip -o -q '${archive}' -d '${dest}'`, { stdio: 'inherit' });
    }
    // Flatten single top-level directory
    flattenSingleDir(dest);
  } else {
    // macOS/Linux: tar
    execSync(`tar -xzf '${archive}' -C '${dest}' --strip-components=1`, { stdio: 'inherit' });
  }
}

function flattenSingleDir(dir) {
  const entries = fs.readdirSync(dir);
  if (entries.length === 1) {
    const inner = path.join(dir, entries[0]);
    if (fs.statSync(inner).isDirectory()) {
      // Move contents up
      const innerEntries = fs.readdirSync(inner);
      for (const e of innerEntries) {
        fs.renameSync(path.join(inner, e), path.join(dir, e));
      }
      fs.rmdirSync(inner);
    }
  }
}

async function main() {
  const javaExe = platform === 'windows' ? 'java.exe' : 'java';
  const jreBinPath = path.join(JRE_DIR, 'bin', javaExe);

  if (existsSync(jreBinPath)) {
    console.log('JRE already exists at', JRE_DIR);
    return;
  }

  mkdirSync(JRE_DIR, { recursive: true });

  try {
    await download(JRE_URL, JRE_ARCHIVE);
    await extractJre(JRE_ARCHIVE, JRE_DIR);
    fs.unlinkSync(JRE_ARCHIVE);
    console.log('JRE installed successfully!');
    console.log(`  Platform: ${platform}, Arch: ${arch}`);
  } catch (err) {
    console.error('Failed to download JRE:', err.message);
    console.error(`  Platform: ${platform}, Arch: ${arch}, URL: ${JRE_URL}`);
    process.exit(1);
  }
}

main();
