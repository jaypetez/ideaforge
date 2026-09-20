// Assemble the publishable static-site tree, with no build step and no dependencies.
//
// The browser harness and Pages deployment both use this exact file list, so the thing tested
// in CI is the thing published to GitHub Pages.

import { copyFile, mkdir, readdir, rm } from 'node:fs/promises';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const ROOT = fileURLToPath(new URL('..', import.meta.url));
export const ROOT_FILES = ['index.html', 'manifest.webmanifest', 'sw.js', 'LICENSE'];
export const ROOT_DIRS = ['src'];

const OMIT = /\.test\.mjs$/;

const toPosix = (path) => path.replace(/\\/g, '/');
const fromPosix = (root, path) => join(root, ...path.split('/'));

async function walk(dir, root = dir) {
  const out = [];
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const file = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...await walk(file, root));
    else out.push(toPosix(relative(root, file)));
  }
  return out;
}

export async function publishableFiles({ root = ROOT } = {}) {
  const directories = await Promise.all(
    ROOT_DIRS.map((dir) => walk(join(root, dir), root)),
  );
  const files = [
    ...ROOT_FILES,
    ...directories.flat(),
  ].filter((file) => !OMIT.test(file));
  return files.sort();
}

export async function assembleSite({ root = ROOT, outDir = join(root, 'dist'), clean = true } = {}) {
  const siteRoot = resolve(root);
  const outputDir = resolve(outDir);
  if (siteRoot === outputDir) {
    throw new Error('refusing to assemble the site over the repository root');
  }

  if (clean) await rm(outputDir, { recursive: true, force: true });
  await mkdir(outputDir, { recursive: true });

  const files = await publishableFiles({ root: siteRoot });
  for (const file of files) {
    const src = fromPosix(siteRoot, file);
    const dest = fromPosix(outputDir, file);
    await mkdir(dirname(dest), { recursive: true });
    await copyFile(src, dest);
  }
  return files;
}

async function main() {
  const outDir = process.argv[2] ? resolve(process.cwd(), process.argv[2]) : join(ROOT, 'dist');
  const files = await assembleSite({ outDir });
  console.log(`assembled ${files.length} files into ${outDir}`);
  for (const file of files) console.log(file);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) await main();
