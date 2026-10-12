import { joinSession } from '@github/copilot-sdk/extension';
import { joinAssistant } from '../../../tools/assistant-copilot.mjs';

// The CLI provides its SDK; there is no npm install, new client or model turn.
await joinAssistant(joinSession);
