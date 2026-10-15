let active = null;

/** A native modal owns focus; only its labelled affirmative action resolves true. */
export async function confirmAction({ title, message, action }) {
  if (active) throw new Error('Finish the open confirmation before opening another.');
  const previous = document.activeElement;
  const dialog = document.createElement('dialog');
  const heading = document.createElement('h2');
  heading.id = 'confirmation-title';
  heading.textContent = title;
  const description = document.createElement('p');
  description.id = 'confirmation-message';
  description.textContent = message;
  dialog.setAttribute('aria-labelledby', heading.id);
  dialog.setAttribute('aria-describedby', description.id);
  const buttons = document.createElement('form');
  buttons.method = 'dialog';
  buttons.className = 'btns';
  for (const [value, label, className] of [
    ['cancel', 'Cancel', 'ghost'], ['confirm', action, 'ghost danger'],
  ]) {
    const button = document.createElement('button');
    button.value = value;
    button.textContent = label;
    button.className = className;
    button.autofocus = value === 'cancel';
    buttons.append(button);
  }
  dialog.append(heading, description, buttons);
  document.body.append(dialog);
  active = dialog;
  try {
    const answer = new Promise((resolve) => {
      dialog.addEventListener('close', () => resolve(dialog.returnValue === 'confirm'), { once: true });
      dialog.addEventListener('cancel', (event) => {
        event.preventDefault();
        dialog.close('cancel');
      });
    });
    dialog.showModal();
    return await answer;
  } finally {
    active = null;
    dialog.remove();
    if (previous?.isConnected && previous.getClientRects().length) previous.focus({ preventScroll: true });
  }
}

/** A retired screen cannot leave an affirmative operation waiting over another idea. */
export function cancelConfirmation() {
  if (active?.open) active.close('cancel');
}
