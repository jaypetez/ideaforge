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

/**
 * Whether this browser can hand a file of `type` to its share sheet, without sharing one.
 *
 * Synchronous on purpose: the Done screen decides its primary action from this as it draws,
 * and the decision must never put an await in front of the navigator.share call a click on
 * that action makes.
 */
export function canShareFile({ filename = 'idea.md', type = 'text/plain' } = {}, {
  navigatorRef = navigator,
  FileCtor = File,
} = {}) {
  if (!navigatorRef || typeof navigatorRef.share !== 'function'
      || typeof navigatorRef.canShare !== 'function') return false;
  try {
    return navigatorRef.canShare({ files: [new FileCtor([''], filename, { type })] }) === true;
  } catch {
    return false;
  }
}

/** Whether this browser has a share sheet at all, even one that only takes text. */
export function canShareText({ navigatorRef = navigator } = {}) {
  return !!navigatorRef && typeof navigatorRef.share === 'function';
}
