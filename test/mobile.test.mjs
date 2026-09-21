import test from 'node:test';
import assert from 'node:assert/strict';

import {
  createInstallController, mobilePlatform, standaloneMode,
} from '../src/ui/install.js';
import { shareTextFile } from '../src/ui/share.js';

class FakeWindow extends EventTarget {
  constructor({ standalone = false } = {}) {
    super();
    this.matches = standalone;
  }

  matchMedia() {
    return { matches: this.matches };
  }
}

class FakeFile {
  constructor(parts, name, options) {
    this.parts = parts;
    this.name = name;
    this.type = options.type;
  }
}

test('mobile platform detection includes iPad desktop-mode user agents', () => {
  assert.equal(mobilePlatform({ userAgent: 'Mozilla/5.0 (Linux; Android 16)', platform: 'Linux' }),
    'android');
  assert.equal(mobilePlatform({ userAgent: 'Mozilla/5.0 (iPhone)', platform: 'iPhone' }), 'ios');
  assert.equal(mobilePlatform({
    userAgent: 'Mozilla/5.0 (Macintosh)', platform: 'MacIntel', maxTouchPoints: 5,
  }), 'ios');
  assert.equal(mobilePlatform({ userAgent: 'Mozilla/5.0 (Windows NT 10.0)', platform: 'Win32' }),
    'other');
});

test('standalone detection accepts display-mode and the iOS navigator flag', () => {
  assert.equal(standaloneMode(new FakeWindow({ standalone: true }), {}), true);
  assert.equal(standaloneMode(new FakeWindow(), { standalone: true }), true);
  assert.equal(standaloneMode(new FakeWindow(), {}), false);
});

test('the Android install prompt is deferred until the user presses the app button', async () => {
  const win = new FakeWindow();
  const states = [];
  const controller = createInstallController({
    win,
    nav: { userAgent: 'Android', platform: 'Linux' },
    onChange: (state) => states.push(state),
  });
  let prompted = 0;
  const event = new Event('beforeinstallprompt');
  Object.assign(event, {
    prompt: async () => { prompted++; },
    userChoice: Promise.resolve({ outcome: 'accepted' }),
  });

  win.dispatchEvent(event);
  assert.equal(controller.state().canPrompt, true);
  const choice = await controller.install();

  assert.equal(choice.outcome, 'accepted');
  assert.equal(prompted, 1);
  assert.equal(controller.state().installed, true);
  assert.ok(states.some((state) => state.canPrompt));
  controller.dispose();
});

test('sharing prefers a markdown file, then falls back to text', async () => {
  const shared = [];
  const fileNavigator = {
    canShare: ({ files }) => files[0].type === 'text/markdown',
    share: async (payload) => shared.push(payload),
  };
  const fileResult = await shareTextFile({
    text: '# Prompt', filename: 'prompt.md', title: 'Prompt', type: 'text/markdown',
  }, { navigatorRef: fileNavigator, FileCtor: FakeFile });

  assert.equal(fileResult.kind, 'file');
  assert.equal(shared[0].files[0].name, 'prompt.md');
  assert.equal(shared[0].files[0].parts[0], '# Prompt');

  const textNavigator = {
    canShare: () => false,
    share: async (payload) => shared.push(payload),
  };
  const textResult = await shareTextFile({
    text: '# Prompt', filename: 'prompt.md', title: 'Prompt', type: 'text/markdown',
  }, { navigatorRef: textNavigator, FileCtor: FakeFile });
  assert.equal(textResult.kind, 'text');
  assert.equal(shared[1].text, '# Prompt');
});

test('sharing treats cancellation as normal and reports unsupported browsers', async () => {
  const cancelled = await shareTextFile({
    text: 'x', filename: 'x.md', title: 'x',
  }, {
    navigatorRef: {
      canShare: () => true,
      share: async () => { throw Object.assign(new Error('cancelled'), { name: 'AbortError' }); },
    },
    FileCtor: FakeFile,
  });
  assert.equal(cancelled.kind, 'cancelled');

  const unsupported = await shareTextFile({
    text: 'x', filename: 'x.md', title: 'x',
  }, { navigatorRef: {}, FileCtor: FakeFile });
  assert.equal(unsupported.kind, 'unsupported');
});
