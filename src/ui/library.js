// DOM for the local ideas library. The app remains the owner of the active session; this
// view receives rows and delegates every mutation back to it.

import { availableTags, filterSessions, libraryCard } from '../core/library.js';

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
  const {
    search, status, tag, rows, empty, count,
    backup, importButton, importFile, newButton,
  } = elements;

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

    const actions = document.createElement('div');
    actions.className = 'btns idea-actions';
    const open = button(session.status === 'done' ? 'View result' : 'Continue', '');
    open.onclick = run(open, () => onOpen(session.id), onError);
    actions.append(open);

    if (card.canExport) {
      const share = button('Share .md');
      share.onclick = run(share, () => onShare(session), onError);
      const download = button('Download .md');
      download.onclick = () => onDownload(session);
      actions.append(share, download);
    }

    const archive = button(card.status === 'archived' ? 'Restore' : 'Archive');
    archive.onclick = run(archive, () =>
      card.status === 'archived' ? onRestore(session) : onArchive(session), onError);
    // Delete looked exactly like Share .md. It is the one action on this card that cannot
    // be undone, so it is the one that gets a different colour. Same label — the browser
    // probe finds it by visible text.
    const remove = button('Delete', 'ghost small danger');
    remove.onclick = run(remove, async () => {
      if (confirmDelete(card.title)) await onDelete(session);
    }, onError);
    actions.append(archive, remove);

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

    article.append(heading, meta, tagList, actions, edit);
    return article;
  }

  function renderRows() {
    const shown = filterSessions(sessions, {
      query: search.value,
      status: status.value,
      tag: tag.value,
    });
    rows.innerHTML = '';
    for (const session of shown) rows.append(renderCard(session));
    count.textContent = `${shown.length} of ${sessions.length} idea${sessions.length === 1 ? '' : 's'}`;
    empty.hidden = shown.length !== 0;
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

  return {
    render(nextSessions) {
      sessions = Array.isArray(nextSessions) ? nextSessions : [];
      renderFilters();
      renderRows();
    },
    focus() {
      search.focus();
    },
  };
}
