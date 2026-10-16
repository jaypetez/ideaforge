import { ProviderError } from '../providers/errors.js';
import { readPrefs, saveInferenceConsent } from './prefs.js';

const REQUIRED = 'Review and agree to send interview text to this provider in Settings before continuing.';

/** A grant identifies one affirmative choice, not a provider key or part of a session. */
export function createInferenceConsent({ onRetire = () => {}, newGrant = () => crypto.randomUUID() } = {}) {
  let kind = null;
  let destination = null;
  let epoch = 0;
  let blocked = false;
  let problem = '';

  function retire(message) {
    epoch++;
    blocked = true;
    problem = message;
    onRetire(message);
  }

  function status(id) {
    const { prefs, error } = readPrefs();
    const allowed = !error && !blocked && !!id && destination === id
      && prefs.inferenceDestination === id && !!prefs.inferenceGrant;
    return {
      allowed,
      permit: allowed ? { destination: id, grant: prefs.inferenceGrant, epoch } : null,
      problem: error ? 'Device preferences could not be read. Interview text will not be sent.'
        : problem,
      error,
    };
  }

  function require(id, expected) {
    const current = status(id);
    if (!current.allowed || (expected !== undefined && (!expected
        || expected.destination !== id || expected.grant !== current.permit.grant
        || expected.epoch !== epoch))) {
      if (current.error && !blocked) retire(current.problem);
      throw new ProviderError('consent', current.problem || REQUIRED, { cause: current.error || undefined });
    }
    return current.permit;
  }

  return {
    status,
    require,
    select(nextKind, nextDestination) {
      const changed = kind !== null && (nextKind !== kind
        || (!!nextDestination && !!destination && nextDestination !== destination));
      kind = nextKind;
      if (nextDestination) destination = nextDestination;
      else if (changed) destination = null;
      if (!changed) return;
      retire('The provider destination changed. Review and agree again before continuing.');
      if (!saveInferenceConsent(destination || '', '')) {
        problem = 'The provider change could not be saved. Interview text remains blocked; retry the agreement.';
      }
    },
    accept(id) {
      if (!id || id !== destination) throw new ProviderError('consent', REQUIRED);
      const generation = epoch;
      const grant = newGrant();
      if (!saveInferenceConsent(id, grant)) {
        retire('The inference agreement could not be saved. Interview text will not be sent; check device storage and retry.');
        throw new ProviderError('consent', problem);
      }
      if (generation !== epoch || destination !== id) {
        throw new ProviderError('consent', 'The provider changed while saving the agreement. Review it again.');
      }
      const saved = readPrefs();
      if (saved.error || saved.prefs.inferenceDestination !== id || saved.prefs.inferenceGrant !== grant) {
        retire('The saved inference agreement could not be verified. Interview text will not be sent.');
        throw new ProviderError('consent', problem, { cause: saved.error || undefined });
      }
      blocked = false;
      problem = '';
      return require(id);
    },
    withdraw() {
      retire('Inference agreement withdrawn. Review and agree again before continuing.');
      if (!saveInferenceConsent(destination || '', '')) {
        problem = 'Inference is blocked on this page, but withdrawal could not be saved. Check device storage before reloading.';
        throw new ProviderError('consent', problem);
      }
    },
  };
}
