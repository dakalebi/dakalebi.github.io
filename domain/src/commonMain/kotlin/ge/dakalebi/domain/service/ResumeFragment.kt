package ge.dakalebi.domain.service

/**
 * [url] with a `#t=` media fragment, so the element starts at [seconds].
 *
 * This is how iOS resumes. iOS ignores a seek issued before it has loaded data, so
 * a seek-based resume there loads the first frames at 0:00 and then loads again at
 * the saved position. The fragment asks for the saved position from the first load.
 *
 * Left out at a second or less, where the watch screen does not resume either, and
 * on a URL that already has a fragment, which a second `#` would break.
 */
fun withStartFragment(url: String, seconds: Int): String =
    if (seconds <= 1 || '#' in url) url else "$url#t=$seconds"
