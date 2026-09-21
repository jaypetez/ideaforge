// The one place the version is written for the running app.
//
// It is duplicated from package.json rather than imported, because importing JSON from an
// ES module in a browser needs an import attribute that is not universally supported yet,
// and this app has no build step to inline it. A test asserts the two never drift.

export const VERSION = '0.4.0';
