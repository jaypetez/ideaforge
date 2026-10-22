// DOM for the local ideas library. The app remains the owner of the active session; this
// view receives rows and delegates every mutation back to it.

import { availableTags, filterSessions, libraryCard } from '../core/library.js';
import { MAX_SESSION_NAME } from '../core/session.js';
import { confirmAction } from './dialog.js';
import { wireOverflowMenu } from './menu.js';

function button(label, className = 'ghost small') {
  const element = document.createElement('button');
  element.type = 'button';
  element.className = className;
  element.textContent = label;
  return element;
}

function run(buttonElement, action, onError) {
  return async () => {
    buttonElement.disabled = true;
    try {
      await action();
    } catch (error) {
      onError(error);
    } finally {
      buttonElement.disabled = false;
    }
  };
}

export function createLibraryView({
  elements,
  onOpen,
  onEdit,
  onArchive,
  onRestore,
  onDelete,
  onShare,
  onDownload,
  onBackup,
  onImport,
  onNew,
  onRetry,
  onError = () => {},
  confirmDelete = (title) => confirmAction({
    title: 'Delete this idea?',
    message: `“${title}” and its interview will be permanently removed from this device.`
      + ' There is no undo; only an existing backup can restore it.',
    action: 'Delete idea',
  }),
  // Draws one idea's export into an element (app.js passes the Done panel's renderer, so the
  // preview is the same rendered markdown, built with textContent, never innerHTML).
  renderPreview = null,
  // The query that decides whether there is room for the detail pane beside the list.
  wide = typeof matchMedia === 'function' ? matchMedia('(min-width: 1024px)') : null,
} = {}) {
  let sessions = [];
  // Whether a read has ever succeeded. Until one has, an empty list means "not known", not
  // "none": a failed first read must not tell someone with saved ideas they have none.
  let loaded = false;
  const {
    search, status, tag, rows, empty, zero, zeroNew, count,
    backup, importButton, importFile, newButton, detail,
    filterSummary, clearFilters, unavailable, retry,
  } = elements;
  // The idea the desktop pane shows. Kept across re-renders and filter changes while it is
  // still listed; meaningless below 1024px, where nothing is marked or shown.
  let selectedId = null;
  let editor = null;
  // The panel says which of its states it is in, so the stylesheet can drop the search and
  // filters when there is nothing to search: 'loading', 'zero' or 'ready'.
  const panel = rows.closest('section') || rows.parentElement;

  function focusIdea(id, index, previous) {
    if (panel.hidden) return;
    const focused = document.activeElement;
    if (focused !== document.body && focused !== previous && focused?.isConnected) return;
    const cards = [...rows.querySelectorAll('.idea-card')];
    const card = cards.find((element) => element.dataset.sessionId === id)
      || cards[Math.min(index, cards.length - 1)];
    const target = card?.querySelector('.idea-open') || panel.querySelector('h2');
    target?.focus();
  }

  async function mutate(session, action) {
    const index = shownSessions().findIndex((row) => row.id === session.id);
    const previous = document.activeElement;
    await action();
    focusIdea(session.id, index, previous);
  }

  function cancelEdit() {
    if (editor?.open) editor.close();
  }

  function editIdea(session, card) {
    if (editor) throw new Error('Finish editing the open idea first.');
    const previous = document.activeElement;
    const index = shownSessions().findIndex((row) => row.id === session.id);
    const dialog = document.createElement('dialog');
    editor = dialog;
    dialog.className = 'idea-editor';
    dialog.setAttribute('aria-labelledby', 'idea-editor-title');
    const heading = document.createElement('h2');
    heading.id = 'idea-editor-title';
    heading.textContent = `Rename or tag “${card.title}”`;
    const form = document.createElement('form');
    const field = (label, id, value) => {
      const wrapper = document.createElement('div');
      wrapper.className = 'field';
      const caption = document.createElement('label');
      caption.htmlFor = id;
      caption.textContent = label;
      const input = document.createElement('input');
      input.id = id;
      input.value = value;
      wrapper.append(caption, input);
      form.append(wrapper);
      return input;
    };
    const name = field('Idea name', 'idea-editor-name', session.name || '');
    name.maxLength = MAX_SESSION_NAME;
    name.placeholder = card.title;
    const tags = field('Tags, separated by commas', 'idea-editor-tags', card.tags.join(', '));
    const error = document.createElement('p');
    error.className = 'field-error';
    error.setAttribute('role', 'alert');
    const actions = document.createElement('div');
    actions.className = 'btns';
    const cancel = button('Cancel', 'ghost');
    cancel.onclick = () => dialog.close();
    const save = button('Save details', '');
    save.type = 'submit';
    actions.append(cancel, save);
    form.append(error, actions);
    form.onsubmit = async (event) => {
      event.preventDefault();
      if (save.disabled) return;
      save.disabled = true;
      // Dismissing a queued save does not cancel the submitted edit.
      cancel.textContent = 'Close';
      name.disabled = true;
      tags.disabled = true;
      save.textContent = 'Saving details…';
      error.textContent = '';
      form.setAttribute('aria-busy', 'true');
      try {
        await onEdit(session, { name: name.value, tags: tags.value.split(',') });
        if (dialog.open) dialog.close();
        else focusIdea(session.id, index, previous);
      } catch (failure) {
        error.textContent = `Details could not be saved. Try again: ${failure.message}`;
        onError(failure);
      } finally {
        save.disabled = false;
        cancel.textContent = 'Cancel';
        name.disabled = false;
        tags.disabled = false;
        save.textContent = 'Save details';
        form.removeAttribute('aria-busy');
      }
    };
    dialog.addEventListener('close', () => {
      if (editor === dialog) editor = null;
      dialog.remove();
      if (panel.hidden) return;
      if (previous?.isConnected) previous.focus();
      else focusIdea(session.id, index, document.activeElement);
    }, { once: true });
    dialog.append(heading, form);
    document.body.append(dialog);
    dialog.showModal();
    name.focus();
  }

  function renderFilters() {
    const selected = tag.value;
    tag.innerHTML = '';
    const all = document.createElement('option');
    all.value = '';
    all.textContent = 'All tags';
    tag.append(all);
    for (const value of availableTags(sessions)) {
      const option = document.createElement('option');
      option.value = value;
      option.textContent = value;
      tag.append(option);
    }
    tag.value = [...tag.options].some((option) => option.value === selected) ? selected : '';
  }

  // The title, status, counts and tags of one idea: the top of its card, and the top of the
  // desktop detail pane, which must say exactly the same thing about the same idea.
  function renderSummary(session, card, headingTag, compact = false) {
    const heading = document.createElement('div');
    heading.className = 'idea-head';
    const title = document.createElement(headingTag);
    title.textContent = card.title;
    const badge = document.createElement('span');
    badge.className = `idea-status ${card.status}`;
    badge.textContent = card.status;
    heading.append(title);
    if (!compact) heading.append(badge);

    const meta = document.createElement('p');
    meta.className = 'idea-meta';
    const updated = card.updatedAt ? new Date(card.updatedAt).toLocaleDateString() : 'unknown date';
    meta.textContent = (compact ? ''
      : `${card.questionCount} question${card.questionCount === 1 ? '' : 's'} · `)
      + `${card.coverage}% coverage · updated ${updated}`;
    if (compact) meta.prepend(badge, ' · ');

    const tagList = document.createElement('div');
    tagList.className = 'idea-tags';
    for (const value of compact ? card.tags.slice(0, 2) : card.tags) {
      const chip = document.createElement('span');
      chip.textContent = value;
      tagList.append(chip);
    }
    if (compact && card.tags.length > 2) {
      const remaining = document.createElement('span');
      remaining.textContent = `+${card.tags.length - 2}`;
      const label = document.createElement('span');
      label.className = 'sr-only';
      label.textContent = ` more tags: ${card.tags.slice(2).join(', ')}`;
      remaining.append(label);
      tagList.append(remaining);
    }
    return { heading, title, meta, tagList };
  }

  // One primary action and the overflow menu beside it. `menuId` is the popover's id, which
  // has to be unique on the page: the card and the detail pane can show the same idea.
  function renderActions(session, card, menuId, row = false) {
    // One primary action per card. Everything else lives in the overflow menu, so the card
    // reads as "this idea, and the one thing you would do next" rather than a toolbar.
    const actions = document.createElement('div');
    actions.className = 'idea-actions';
    const label = session.status === 'done' ? 'View result' : 'Continue';
    const open = button(row && isWide() ? 'Details' : label,
      row ? 'quiet small idea-open idea-select' : '');
    open.dataset.openLabel = label;
    open.onclick = run(open, () => row && isWide()
      ? select(session.id, { focus: true }) : onOpen(session.id), onError);

    // A native popover: Esc and light dismiss close it, and focus goes back to the trigger,
    // with no script of ours to get wrong. The buttons keep their exact labels, because the
    // browser probes find them by visible text once the menu is open.
    const menu = document.createElement('div');
    menu.className = 'idea-menu';
    menu.id = menuId;
    menu.popover = 'auto';
    menu.setAttribute('aria-label', `Actions for ${card.title}`);
    const more = button('', 'quiet idea-more');
    more.dataset.icon = 'more-vert';
    more.setAttribute('aria-label', `More actions for ${card.title}`);
    actions.append(open, more);

    const item = (label, icon, className = 'quiet') => {
      const element = button(label, `${className} idea-menu-item`);
      element.dataset.icon = icon;
      menu.append(element);
      return element;
    };
    // Closing the menu first, synchronously, matters twice over. It hands focus back to the
    // trigger before the card can re-render, and Share must still reach navigator.share inside
    // the click gesture: hidePopover() and run() add no await before onShare is called.
    // wireOverflowMenu (menu.js) places it under its trigger, flips it near the bottom,
    // closes it on resize and moves focus into it.
    const close = wireOverflowMenu(menu, more);

    const edit = item('Rename or tag', 'edit-note');
    edit.onclick = () => {
      close();
      try { editIdea(session, card); } catch (error) { onError(error); }
    };

    if (card.canExport) {
      const share = item('Share .md', 'share');
      const shareAction = run(share, () => onShare(session), onError);
      share.onclick = () => { close(); shareAction(); };
      const download = item('Download .md', 'download');
      download.onclick = () => { close(); onDownload(session); };
    }

    const archived = card.status === 'archived';
    const archive = item(archived ? 'Restore' : 'Archive', archived ? 'unarchive' : 'archive');
    const archiveAction = run(archive, () => mutate(session, () =>
      archived ? onRestore(session) : onArchive(session)), onError);
    archive.onclick = () => { close(); archiveAction(); };
    // Delete is the one action on this card that cannot be undone, so it is the one that
    // gets a different colour, and it still asks first. Same label — the browser probe finds
    // it by visible text.
    const remove = item('Delete', 'delete', 'quiet danger');
    const removeAction = run(remove, async () => {
      if (await confirmDelete(card.title)) await mutate(session, () => onDelete(session));
    }, onError);
    remove.onclick = () => { close(); removeAction(); };
    return { actions, menu };
  }

  function renderCard(session) {
    const card = libraryCard(session);
    const article = document.createElement('article');
    article.className = 'idea-card';
    article.dataset.sessionId = session.id;

    const { heading, meta, tagList } = renderSummary(session, card, 'h3', true);
    const { actions, menu } = renderActions(session, card, `idea-menu-${session.id}`, true);
    const summary = document.createElement('div');
    summary.className = 'idea-summary';
    summary.append(heading, meta, tagList);

    // Clicking the card's body selects it, on a wide screen. Anything interactive inside the
    // card keeps its own meaning: the primary still navigates, the menu still opens, and the
    // rename fields still take a click without the pane changing under them.
    article.addEventListener('click', (event) => {
      if (!isWide()) return;
      if (event.target.closest('button, a, input, select, textarea, label, details, [popover]')) return;
      select(session.id);
    });

    article.append(summary, actions, menu);
    return article;
  }

  // ─────────────────────────────────────────────── desktop detail pane (≥1024px)
  //
  // The row's primary selects on wide screens and opens directly on compact screens.

  function isWide() {
    return Boolean(detail && wide && wide.matches);
  }

  function shownSessions() {
    return filterSessions(sessions, {
      query: search.value,
      status: status.value,
      tag: tag.value,
    });
  }

  function select(id, { focus = false } = {}) {
    if (!isWide()) return;
    selectedId = id;
    syncSelection();
    if (focus) {
      const title = detail.querySelector('.library-detail-title');
      if (title) title.focus({ preventScroll: true });
    }
  }

  /**
   * Make the selection, the cards' marks and the pane agree. Called after every render and
   * whenever the wide query flips. A selection the filters have hidden (or that was
   * deleted) falls to the first card shown, so the pane never describes an idea the list
   * does not.
   */
  function syncSelection() {
    if (!detail) return;
    const wideNow = isWide() && loaded;
    const shown = wideNow ? shownSessions() : [];
    if (wideNow && !shown.some((session) => session.id === selectedId)) {
      selectedId = shown.length ? shown[0].id : null;
    }
    for (const article of rows.querySelectorAll('.idea-card')) {
      const on = wideNow && article.dataset.sessionId === selectedId;
      if (on) article.setAttribute('aria-current', 'true');
      else article.removeAttribute('aria-current');
      const pick = article.querySelector('.idea-select');
      if (pick) {
        pick.textContent = wideNow ? 'Details' : pick.dataset.openLabel;
        const title = article.querySelector('h3').textContent;
        pick.setAttribute('aria-label', `${wideNow ? 'Show details for' : pick.textContent} ${title}`);
        if (wideNow) {
          pick.setAttribute('aria-pressed', String(on));
          pick.setAttribute('aria-controls', detail.id);
        } else {
          pick.removeAttribute('aria-pressed');
          pick.removeAttribute('aria-controls');
        }
      }
    }
    renderDetail(wideNow ? shown.find((session) => session.id === selectedId) : null);
  }

  function renderDetail(session) {
    // Close a menu still open from the previous idea before its node goes.
    const open = detail.querySelector('[popover]');
    if (open && open.matches(':popover-open')) open.hidePopover();
    detail.textContent = '';
    detail.dataset.sessionId = session ? session.id : '';
    if (!session) {
      // Only when there is something listed to select: never beside the loading
      // placeholders, and never beside "No ideas match these filters".
      if (isWide() && loaded && !rows.hasAttribute('aria-busy') && shownSessions().length) {
        const hint = document.createElement('p');
        hint.className = 'hint library-detail-empty';
        hint.textContent = 'Select an idea to see it here.';
        detail.append(hint);
      }
      return;
    }
    const card = libraryCard(session);
    const { heading, title, meta, tagList } = renderSummary(session, card, 'h3');
    title.className = 'library-detail-title';
    title.tabIndex = -1;
    const { actions, menu } = renderActions(session, card, 'library-detail-menu');

    const preview = document.createElement('div');
    preview.className = 'output library-preview';
    preview.setAttribute('aria-label', `Preview of ${card.title}`);
    preview.setAttribute('role', 'document');
    let drawn = false;
    if (!session.synthesis.text && session.status !== 'done') {
      const heading = document.createElement('h4');
      heading.textContent = 'No write-up yet';
      const hint = document.createElement('p');
      hint.textContent = session.status === 'synthesizing'
        ? 'The write-up has not finished. Continue to return to it.'
        : 'Continue the interview; your questions, answers and draft are saved here.';
      preview.append(heading, hint);
      const latest = session.turns.at(-1);
      const answered = [...session.turns].reverse().find((turn) => turn.answer);
      for (const [label, text] of [
        ['Latest question', latest?.question], ['Latest answer', answered?.answer],
        ['Unfinished draft', session.draftAnswer],
      ]) {
        if (!text) continue;
        const paragraph = document.createElement('p');
        const caption = document.createElement('strong');
        caption.textContent = `${label}: `;
        paragraph.append(caption, document.createTextNode(text));
        preview.append(paragraph);
      }
      drawn = true;
    } else if (card.canExport && renderPreview) {
      try {
        renderPreview(preview, session);
        drawn = preview.childNodes.length > 0;
      } catch (error) {
        onError(error);
      }
    }
    if (!drawn) {
      preview.textContent = '';
      preview.classList.add('library-preview-empty');
      const hint = document.createElement('p');
      hint.className = 'hint';
      hint.textContent = card.canExport
        ? 'This idea could not be previewed.'
        : 'Nothing to preview yet. Answer the first question and the write-up starts here.';
      preview.append(hint);
    }
    detail.append(heading, meta, tagList, actions, menu, preview);
  }

  function renderRows() {
    const shown = shownSessions();
    filterSummary.textContent = `${status.selectedOptions[0].textContent} · ${tag.value || 'All tags'}`;
    clearFilters.hidden = !search.value && status.value === 'all' && !tag.value;
    rows.innerHTML = '';
    rows.removeAttribute('aria-busy');
    unavailable.hidden = loaded;
    if (!loaded) {
      panel.dataset.libraryState = 'unavailable';
      // renderLoading said "Loading ideas…"; a read that failed must not leave that behind.
      count.textContent = 'Ideas unavailable';
      zero.hidden = true;
      empty.hidden = true;
      syncSelection();
      return;
    }
    for (const session of shown) rows.append(renderCard(session));
    count.textContent = `${shown.length} of ${sessions.length} idea${sessions.length === 1 ? '' : 's'}`;
    // Two different empties. No ideas at all is a first run and wants a way to start one;
    // filters that match nothing want the filters, which stay on screen beside the message.
    const none = sessions.length === 0;
    panel.dataset.libraryState = none ? 'zero' : 'ready';
    zero.hidden = !none;
    empty.hidden = none || shown.length !== 0;
    syncSelection();
  }

  // Placeholder cards while IndexedDB answers. They are decoration, so they are hidden from
  // assistive technology, and the list announces itself busy instead. Not .idea-card: the
  // screenshot harness and the probes count those as real ideas.
  function renderLoading(placeholders = 3) {
    rows.innerHTML = '';
    rows.setAttribute('aria-busy', 'true');
    panel.dataset.libraryState = 'loading';
    // The placeholder bars are decoration at low contrast, so the loading state is said in
    // text as well (#111). The shipped "0 ideas" would otherwise claim an empty library.
    count.textContent = 'Loading ideas…';
    zero.hidden = true;
    empty.hidden = true;
    unavailable.hidden = true;
    for (let i = 0; i < placeholders; i++) {
      const skeleton = document.createElement('div');
      skeleton.className = 'idea-skeleton';
      skeleton.setAttribute('aria-hidden', 'true');
      for (const part of ['title', 'meta', 'action']) {
        const bar = document.createElement('span');
        bar.className = `idea-skeleton-${part}`;
        skeleton.append(bar);
      }
      rows.append(skeleton);
    }
    if (detail) renderDetail(null);
  }

  search.addEventListener('input', renderRows);
  status.addEventListener('change', renderRows);
  tag.addEventListener('change', renderRows);
  clearFilters.onclick = () => {
    search.value = '';
    status.value = 'all';
    tag.value = '';
    renderRows();
    search.focus();
  };
  retry.onclick = run(retry, async () => {
    renderLoading();
    await onRetry();
  }, onError);
  backup.onclick = run(backup, onBackup, onError);
  importButton.onclick = () => importFile.click();
  importFile.onchange = async () => {
    const file = importFile.files && importFile.files[0];
    importFile.value = '';
    if (!file) return;
    try {
      await onImport(file);
    } catch (error) {
      onError(error);
    }
  };
  newButton.onclick = run(newButton, onNew, onError);
  // Crossing 1024px either way fills or empties the pane without rebuilding the cards, so a
  // focused card control keeps its focus through a window resize.
  if (wide && detail) {
    const onWideChange = () => syncSelection();
    if (typeof wide.addEventListener === 'function') wide.addEventListener('change', onWideChange);
    else if (typeof wide.addListener === 'function') wide.addListener(onWideChange);
  }
  zeroNew.onclick = run(zeroNew, onNew, onError);

  return {
    loading() {
      renderLoading();
    },
    /** Leave the loading state without new rows, after a failed read. Before any read has
        succeeded this shows neither empty state; the error toast says what happened. */
    settle() {
      if (rows.hasAttribute('aria-busy')) renderRows();
    },
    render(nextSessions) {
      sessions = Array.isArray(nextSessions) ? nextSessions : [];
      loaded = true;
      renderFilters();
      renderRows();
    },
    focus() {
      search.focus();
    },
    cancelEdit,
  };
}
