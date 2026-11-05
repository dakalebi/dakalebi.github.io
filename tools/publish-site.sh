#!/usr/bin/env bash
# Writes one folder of the published site and pushes the result to `gh-pages`.
#
#   tools/publish-site.sh <folder> [<build>]
#
#   <folder>  "." for the site root, or "pr/<number>". Only that folder
#             changes; everything else on the site is kept as it is.
#   <build>   the directory to publish there. Omit it to remove the folder.
#
#   PUBLISH_REMOTE   the repository to push to (required)
#   PUBLISH_MESSAGE  the commit message (optional)
#
# `gh-pages` is the state of the site, kept as ONE commit with no history. Every
# publish carries a full copy of the bundle, about 0.8 MB packed, and with a
# preview per pull request that history would grow by megabytes a day for
# nothing anyone reads: the Deployments page already records what went live and
# when, and rolling back production is a revert on `master`.
#
# Writers never coordinate with each other. Each one starts from the newest tip,
# changes only its own folder, and replaces the tip only if it is still the tip
# it started from (`--force-with-lease`). If another writer got there first, it
# starts again from the new tip, so no writer can drop another one's change.
set -euo pipefail

folder="${1:?usage: publish-site.sh <folder> [<build>]}"
build="${2:-}"
remote="${PUBLISH_REMOTE:?PUBLISH_REMOTE is not set}"
message="${PUBLISH_MESSAGE:-Publish ${folder}}"
branch=gh-pages

# The top-level folders other writers own. Publishing the root replaces
# everything at the top level except these, so a folder nothing owns any more
# (the old /preview, say) goes with the next production publish.
owned_elsewhere=(pr)

if [ "$folder" = "." ]; then
  if [ -z "$build" ]; then
    echo "::error::The site root cannot be removed, only replaced." >&2
    exit 1
  fi
elif ! [[ "$folder" =~ ^pr/[1-9][0-9]*$ ]]; then
  echo "::error::'${folder}' is not a folder anything publishes to." >&2
  exit 1
fi

# A build without its page would publish a folder that serves nothing.
if [ -n "$build" ] && [ ! -f "$build/index.html" ]; then
  echo "::error::'${build}' has no index.html, so it is not a site build." >&2
  exit 1
fi

export GIT_AUTHOR_NAME="${GIT_AUTHOR_NAME:-github-actions[bot]}"
export GIT_AUTHOR_EMAIL="${GIT_AUTHOR_EMAIL:-41898282+github-actions[bot]@users.noreply.github.com}"
export GIT_COMMITTER_NAME="${GIT_COMMITTER_NAME:-$GIT_AUTHOR_NAME}"
export GIT_COMMITTER_EMAIL="${GIT_COMMITTER_EMAIL:-$GIT_AUTHOR_EMAIL}"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

attempts=10
for attempt in $(seq 1 "$attempts"); do
  site="$work/site-$attempt"

  # Exit code 2 is "no such ref", the only failure that means "start the site".
  # Anything else (network, auth) has to stop the publish, not empty the site.
  if git ls-remote --exit-code "$remote" "refs/heads/$branch" > /dev/null; then
    git clone -q --depth 1 --branch "$branch" "$remote" "$site"
    base="$(git -C "$site" rev-parse HEAD)"
  else
    status=$?
    if [ "$status" -ne 2 ]; then
      exit "$status"
    fi
    echo "${branch} does not exist yet; starting it."
    git init -q -b "$branch" "$site"
    git -C "$site" remote add origin "$remote"
    base=""
  fi

  if [ "$folder" = "." ]; then
    keep=(! -name .git)
    for name in "${owned_elsewhere[@]}"; do
      keep+=(! -name "$name")
    done
    find "$site" -mindepth 1 -maxdepth 1 "${keep[@]}" -exec rm -rf {} +
    cp -R "$build/." "$site/"
  else
    rm -rf "${site:?}/${folder}"
    if [ -n "$build" ]; then
      mkdir -p "$site/$folder"
      cp -R "$build/." "$site/$folder/"
    fi
  fi

  # Both files belong to the site rather than to any one build, so every
  # publish writes them. Pages runs Jekyll by default, and Jekyll drops files
  # whose names begin with an underscore, which Kotlin/JS and webpack emit. The
  # copies under /pr are the production app on the same host, so leaving them
  # indexable would have them competing with it in search results.
  touch "$site/.nojekyll"
  printf 'User-agent: *\nDisallow: /pr/\n' > "$site/robots.txt"

  git -C "$site" add -A
  tree="$(git -C "$site" write-tree)"
  if [ -n "$base" ] && [ "$tree" = "$(git -C "$site" rev-parse "${base}^{tree}")" ]; then
    echo "${folder} is already published exactly as built; nothing to push."
    exit 0
  fi

  commit="$(git -C "$site" commit-tree "$tree" -m "$message")"
  if git -C "$site" push -q --force-with-lease="refs/heads/${branch}:${base}" \
    origin "${commit}:refs/heads/${branch}"; then
    echo "Published ${folder} as ${commit}."
    exit 0
  fi

  echo "${branch} moved during the publish (attempt ${attempt}); starting again from the new tip."
  # Random, so writers that collided once do not collide again in lockstep.
  sleep $((RANDOM % (attempt * 2) + 1))
done

echo "::error::Could not publish ${folder} after ${attempts} attempts." >&2
exit 1
