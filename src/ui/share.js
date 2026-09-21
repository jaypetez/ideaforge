// Browser adapters for outbound files. Core builds the content; this module only hands it
// to the operating system or downloads it.

export function downloadText(text, filename, {
  documentRef = document,
  urlRef = URL,
  BlobCtor = Blob,
  type = 'text/plain',
} = {}) {
  const blob = new BlobCtor([text], { type });
  const url = urlRef.createObjectURL(blob);
  const anchor = documentRef.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  setTimeout(() => urlRef.revokeObjectURL(url), 1000);
}

export async function shareTextFile({
  text,
  filename,
  title,
  type = 'text/plain',
}, {
  navigatorRef = navigator,
  FileCtor = File,
} = {}) {
  if (!navigatorRef || typeof navigatorRef.share !== 'function') {
    return { kind: 'unsupported' };
  }

  const file = new FileCtor([text], filename, { type });
  try {
    if (typeof navigatorRef.canShare === 'function'
        && navigatorRef.canShare({ files: [file] })) {
      await navigatorRef.share({ title, files: [file] });
      return { kind: 'file' };
    }
    await navigatorRef.share({ title, text });
    return { kind: 'text' };
  } catch (error) {
    if (error && error.name === 'AbortError') return { kind: 'cancelled' };
    throw error;
  }
}
