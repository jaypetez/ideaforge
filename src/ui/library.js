// DOM for the local ideas library. The app remains the owner of the active session; this
// view receives rows and delegates every mutation back to it.

import { availableTags, filterSessions, libraryCard } from '../core/library.js';
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
  onError = () => {},
  confirmDelete = (title) => confirm(`Permanently delete “${title}”?`),
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
  } = elements;
  // The idea the desktop pane shows. Kept across re-renders and filter changes while it is
  // still listed; meaningless below 1024px, where nothing is marked or shown.
  let selectedId = null;
  // The panel says which of its states it is in, so the stylesheet can drop the search and
  // filters when there is nothing to search: 'loading', 'zero' or 'ready'.
  const panel = rows.closest('section') || rows.parentElement;

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
  function renderSummary(session, card, headingTag) {
    const heading = document.createElement('div');
    heading.className = 'idea-head';
    const title = document.createElement(headingTag);
    title.textContent = card.title;
    const badge = document.createElement('span');
    badge.className = `idea-status ${card.status}`;
    badge.textContent = card.status;
    heading.append(title, badge);

    const meta = document.createElement('p');
    meta.className = 'idea-meta';
    const updated = card.updatedAt ? new Date(card.updatedAt).toLocaleDateString() : 'unknown date';
    meta.textContent = `${card.questionCount} question${card.questionCount === 1 ? '' : 's'}`
      + ` · ${card.coverage}% coverage · updated ${updated}`;

    const tagList = document.createElement('div');
    tagList.className = 'idea-tags';
    for (const value of card.tags) {
      const chip = document.createElement('span');
      chip.textContent = value;
      tagList.append(chip);
    }
    return { heading, title, meta, tagList };
  }

  // One primary action and the overflow menu beside it. `menuId` is the popover's id, which
  // has to be unique on the page: the card and the detail pane can show the same idea.
  function renderActions(session, card, menuId) {
    // One primary action per card. Everything else lives in the overflow menu, so the card
    // reads as "this idea, and the one thing you would do next" rather than a toolbar.
    const actions = document.createElement('div');
    actions.className = 'idea-actions';
    const open = button(session.status === 'done' ? 'View result' : 'Continue', '');
    open.onclick = run(open, () => onOpen(session.id), onError);

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

    if (card.canExport) {
      const share = item('Share .md', 'share');
      const shareAction = run(share, () => onShare(session), onError);
      share.onclick = () => { close(); shareAction(); };
      const download = item('Download .md', 'download');
      download.onclick = () => { close(); onDownload(session); };
    }

    const archived = card.status === 'archived';
    const archive = item(archived ? 'Restore' : 'Archive', archived ? 'unarchive' : 'archive');
    const archiveAction = run(archive, () =>
      archived ? onRestore(session) : onArchive(session), onError);
    archive.onclick = () => { close(); archiveAction(); };
    // Delete is the one action on this card that cannot be undone, so it is the one that
    // gets a different colour, and it still asks first. Same label — the browser probe finds
    // it by visible text.
    const remove = item('Delete', 'delete', 'quiet danger');
    const removeAction = run(remove, async () => {
      if (confirmDelete(card.title)) await onDelete(session);
    }, onError);
    remove.onclick = () => { close(); removeAction(); };
    return { actions, menu };
  }

  function renderCard(session) {
    const card = libraryCard(session);
    const article = document.createElement('article');
    article.className = 'idea-card';
    article.dataset.sessionId = session.id;

    const { heading, meta, tagList } = renderSummary(session, card, 'h3');
    const { actions, menu } = renderActions(session, card, `idea-menu-${session.id}`);

    // Desktop only (app.css hides it below 1024px, where there is no detail pane): show this
    // idea in the pane beside the list. A button of its own, so the keyboard has a way in;
    // the card body is the pointer's shortcut to the same thing (see the click handler below).
    const details = button('Details', 'quiet small idea-select');
    details.setAttribute('aria-controls', detail ? detail.id : '');
    details.setAttribute('aria-label', `Show details for ${card.title}`);
    details.onclick = () => select(session.id, { focus: true });
    heading.append(details);

    const edit = document.createElement('details');
    edit.className = 'idea-edit';
    const summary = document.createElement('summary');
    summary.textContent = 'Rename or tag';
    const nameLabel = document.createElement('label');
    nameLabel.textContent = 'Idea name';
    // These labels wrapped nothing and pointed at nothing, so both inputs were announced
    // unlabelled. The id is per session because several cards are open at once.
    nameLabel.htmlFor = `idea-name-${session.id}`;
    const name = document.createElement('input');
    name.id = nameLabel.htmlFor;
    name.value = session.name || '';
    name.placeholder = card.title;
    name.maxLength = 120;
    const tagsLabel = document.createElement('label');
    tagsLabel.textContent = 'Tags';
    tagsLabel.htmlFor = `idea-tags-${session.id}`;
    const tags = document.createElement('input');
    tags.id = tagsLabel.htmlFor;
    tags.value = card.tags.join(', ');
    tags.placeholder = 'work, writing, product';
    const save = button('Save details', '');
    save.onclick = run(save, async () => {
      await onEdit(session, {
        name: name.value,
        tags: tags.value.split(','),
      });
      edit.open = false;
    }, onError);
    edit.append(summary, nameLabel, name, tagsLabel, tags, save);

    // Clicking the card's body selects it, on a wide screen. Anything interactive inside the
    // card keeps its own meaning: the primary still navigates, the menu still opens, and the
    // rename fields still take a click without the pane changing under them.
    article.addEventListener('click', (event) => {
      if (!isWide()) return;
      if (event.target.closest('button, a, input, select, textarea, label, details, [popover]')) return;
      select(session.id);
    });

    article.append(heading, meta, tagList, actions, menu, edit);
    return article;
  }

  // ─────────────────────────────────────────────── desktop detail pane (≥1024px)
  //
  // Wide screens show the list on the left and one idea on the right. The pane is filled
  // only while the wide query matches: below it there is no pane, and the cards' DOM is
  // what it always was, so nothing narrower can find a second "Continue" or "Delete".

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
      if (pick) pick.setAttribute('aria-pressed', String(on));
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
    if (card.canExport && renderPreview) {
      try {
        renderPreview(preview, session);
        drawn = preview.childNodes.length > 0;
      } catch (error) {
        drawn = false;
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
    rows.innerHTML = '';
    rows.removeAttribute('aria-busy');
    if (!loaded) {
      panel.dataset.libraryState = 'unavailable';
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
  };
}
