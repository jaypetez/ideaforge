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
} = {}) {
  let sessions = [];
  // Whether a read has ever succeeded. Until one has, an empty list means "not known", not
  // "none": a failed first read must not tell someone with saved ideas they have none.
  let loaded = false;
  const {
    search, status, tag, rows, empty, zero, zeroNew, count,
    backup, importButton, importFile, newButton,
  } = elements;
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

  function renderCard(session) {
    const card = libraryCard(session);
    const article = document.createElement('article');
    article.className = 'idea-card';
    article.dataset.sessionId = session.id;

    const heading = document.createElement('div');
    heading.className = 'idea-head';
    const title = document.createElement('h3');
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
    menu.id = `idea-menu-${session.id}`;
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

    article.append(heading, meta, tagList, actions, menu, edit);
    return article;
  }

  function renderRows() {
    const shown = filterSessions(sessions, {
      query: search.value,
      status: status.value,
      tag: tag.value,
    });
    rows.innerHTML = '';
    rows.removeAttribute('aria-busy');
    if (!loaded) {
      panel.dataset.libraryState = 'unavailable';
      zero.hidden = true;
      empty.hidden = true;
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
  }

  // Placeholder cards while IndexedDB answers. They are decoration, so they are hidden from
  // assistive technology, and the list announces itself busy instead. Not .idea-card: the
  // screenshot harness and the probes count those as real ideas.
  function renderLoading(placeholders = 3) {
    rows.innerHTML = '';
    rows.setAttribute('aria-busy', 'true');
    panel.dataset.libraryState = 'loading';
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
