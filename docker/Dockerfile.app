# IdeaForge as a static site, for running it locally against a local model.
#
# There is no build step anywhere in this project, so there is nothing to compile and no
# toolchain to install: the tree that ships is the tree in git. The file list below is the
# same one .github/workflows/pages.yml publishes, kept deliberately in step with it — if
# you add a file to one, add it to the other.
#
# nginx only has to serve bytes over a real origin. The Content-Security-Policy lives in a
# meta tag inside index.html, so there is no server config to get wrong, and ES modules,
# IndexedDB and the service worker all need an origin rather than file://.

FROM nginx:1.27-alpine

# Not COPY . — the app image has no business containing the tests, the docs, the day-0
# spikes or the git history.
COPY index.html manifest.webmanifest sw.js LICENSE /usr/share/nginx/html/
COPY src/ /usr/share/nginx/html/src/

# Belt and braces: .dockerignore should already have excluded these.
RUN find /usr/share/nginx/html -name '*.test.mjs' -delete

EXPOSE 80
