## On the Vichan Posting WAF (Web Application Firewall)
Vichan docs are severely lacking and this [repo](https://github.com/vichan-devel/vichan-API/) is the only thing mentioning an API.
After adding a lot of debugging to `VichanAntispam.java`, `NetModule.java` and `MultipartHttpCall.java` we have a clearer picture of how Vichan works:

Vichan's bot protection relies on filtering out clients that submit programmatic "perfect" requests. In particular, automated tools typically send rigid, hardcoded lists of keys without regard for session state or acknowledging empty HTML forms.

To complete a valid post without getting the *"You look like a bot."* or *"Your request looks automated; Post discarded"* error (this was tested on Sushichan, so other sites might differ in their posting rules/spam criteria), several criteria must be met in unison across two phases:

### Phase 1: The Preparatory Stage (VichanAntispam.java)

**The Cookie Generation:**\
First we deliberately do an empty `GET /post.php` request.\
Vichan requires the client to establish a live *$PHPSESSID* session cookie to prove they've loaded the posting page. We fetch the target thread's HTML, parse the DOM with Jsoup, and locate the actual HTML `<form>`. We extract all hidden form tokens, which have randomized garbage names (such as yk1qhj...). A bot would normally miss these because they only exist in that dynamic page render.

To trick bots and scrapers, Vichan renames the "Comment" box inside the text area dynamically to something else (this changes per thread). We detect the renamed `<textarea>` and explicitly dump our native `reply.comment` into it.

### Phase 2: The API Delivery (VichanActions.java)

We construct the MultipartBody payload exactly as a real browsing engine would.
We append the hardcoded basic fields board, thread, and submit all form fields in DOM order exactly as they appear in the parsed HTML form.

Previously, if a user did not put a Title on their post, Clover logically dropped the subject from the MultipartHttpCall entirely (as in `if (!isEmpty(reply.subject))`).\
However, a real web browser form-data naturally submits empty fields. The `subject=""` parameter was missing from our initial payload footprint, making Clover immediately be identified as a script or bot.

Clover was also missing the hidden form keys, randomly generated body `<textarea>` IDs, and the proper *$PHPSESSID* tracking.

### The `json_response` Parameter

Vichan's `post.php` checks for `$_POST['json_response']`; when present, the server returns a JSON object like `{"id": 12345, "tid": 2164, "redirect": "/hell/res/2164.html#12345", "noko": true}` instead of issuing an HTTP redirect.

In the **infinity fork** (8chan-based), `json_response` is listed in `$config['spam']['valid_inputs']`, so including it as a POST field does **not** affect the antispam hash calculation. However, in **vanilla vichan**, `json_response` is **not** in the default `valid_inputs` array. Adding it as a POST field on vanilla vichan would cause it to be counted as an antispam field — since it wasn't present in the original HTML form, the reconstructed SHA1 hash would not match, and the post would be rejected.

Because we cannot know at runtime which vichan variant a site uses ($config is server-side only), we do **not** send `json_response` and instead handle the redirect-based response flow. This is the safest approach for maximum compatibility across all vichan-derived sites.

### The Textarea Whitespace Bug (March 2026)

The antispam was intermittently failing with *"Your request looks automated; Post discarded"*. The root cause was `Element.val()` on `<textarea>` elements in Jsoup 1.13.1, which delegates to `text()` which **trims leading/trailing whitespace and normalizes interior whitespace** (collapses consecutive spaces to a single space). 

Vichan's `AntiBot::randomString()` includes the **space character** in its charset (`~!@#$%^&*()_+,./;'[]\{}|:<>?=-`), so antispam values placed inside `<textarea style="display:none">` elements can legitimately contain leading/trailing/consecutive spaces. When Jsoup's `text()` stripped these, the value submitted to the server differed from the original, causing the reconstructed antispam SHA1 hash to not match the stored hash — triggering rejection.

**Failure was intermittent** because it required: (a) the server randomly choosing a `<textarea>` element template for an antispam field (~18% chance per field, based on the element template pool in `anti-bot.php`), AND (b) the generated random value happening to contain whitespace that Jsoup would normalize.

**Fix**: Use `TextNode.getWholeText()` to read textarea content with exact whitespace preservation instead of `Element.val()`. Also improved `isHiddenElement()` to handle CSS variations like `display: none` (with space after colon) via regex patterns instead of exact string matching.

### The User-Agent Override Bug (March 2026)

`ChanInterceptor.java` (OkHttp interceptor) was unconditionally overwriting the `User-Agent` header with the default Android WebView UA, even when site-specific code (e.g., `VichanAntispam.java`'s GET request or `Sushichan.java`/`Lainchan.java`'s `CommonRequestModifier`) had already set a desktop Chrome UA.

This caused the antispam form fetch and the POST submission to use different User-Agent strings — the GET used the desktop Chrome UA but the POST used the Android WebView UA. While this didn't break the antispam hash directly (User-Agent is not part of the hash), it made the client's request fingerprint inconsistent, potentially triggering additional server-side heuristics.

**Fix**: Changed `ChanInterceptor` to only set `User-Agent` and `Accept` headers conditionally — if they haven't already been set on the request.

### The "Error posting: unknown response" Bug (March 2026)

After fixing the antispam issues, posts were going through successfully on the server side, but the app was displaying *"Error posting: unknown response"* to the user.

**Root cause**: When `json_response` is not in the POST data, vichan's `post.php` issues an HTTP 303 redirect instead of returning JSON:
- **With noko** (email field = "noko"): redirects to `/board/res/thread.html#postNo`
- **Without noko** (empty email): redirects to the board index (`/board/` or `/board/index.html`)

OkHttp follows redirects by default, so `handlePost()` receives a 200 response containing the final destination's HTML (either the thread page or the board index). The original `handlePost` code only checked:
1. JSON parsing (fails — it's HTML)
2. Error pattern matching `<h1>Error</h1>...<h2>...</h2>` (no error on a success page)
3. URL pattern `/\w+/res/(\d+).html` against the final URL (fails when redirected to board index)
4. Text match "Post successful" / "Thread created" (not present in normal HTML pages)
5. Falls through to **"Error posting: unknown response"**

**Fix**: Added redirect chain detection using OkHttp's `response.priorResponse()`. When a prior response in the chain is a redirect (3xx), the post was accepted by the server. Thread/post numbers are extracted via three fallback strategies:
1. **Location header**: Parse the redirect's `Location` header for `/board/res/thread.html#postNo`
2. **Final URL**: Check the URL after redirect following (handles noko case where final URL is the thread)
3. **`serv` cookie**: Vichan sets a `Set-Cookie: serv={"id":<postNo>}` cookie after a successful post (cookie name from `$config['cookies']['js']`, defaults to `serv`). This works even for board-index redirects (no noko) where the post number isn't in the URL.

### The Hash Validation Algorithm

From vichan's `inc/anti-bot.php` (`AntiBot` class in the infinity fork):

1. Server generates 4–12 random hidden form fields with garbage names/values (charset includes Unicode, spaces, and special characters)
2. Fields are rendered as `<input type="hidden">` or `<textarea style="display:none">` in the post form HTML
3. Server computes SHA1 hash of sorted fields + salt, stores it in the database
4. On POST submission, server filters out `$config['spam']['valid_inputs']` from `$_POST`:
   - Default valid inputs: `hash`, `board`, `thread`, `name`, `email`, `subject`, `body`, `password`, `embed`, `post`, `spoiler`, `file`
   - Infinity fork also includes: `mod`, `sticky`, `lock`, `raw`, `recaptcha_challenge_field`, `recaptcha_response_field`, `captcha_cookie`, `captcha_text`, `page`, `file_url`, `json_response`, `user_flag`, `no_country`, `tag`
5. Remaining POST fields = antispam fields
6. Sort alphabetically by key (`ksort`)
7. Concatenate as `key=value` (no separator between pairs)
8. Append cookie salt (`$config['cookies']['salt']`) + board:thread salt
9. SHA1 → must match the submitted `hash` field
10. Each hash can be used `$config['spam']['hidden_inputs_max_pass']` times (default: 12) before expiring
11. Old hashes expire after `$config['spam']['hidden_inputs_expire']` (default: 3 hours)

### The Minimum Fetch-to-Submit Delay

Vichan tracks when antispam hashes are generated. A real user would look at the page for at least a few seconds before posting. To avoid triggering timing-based bot detection, we enforce a minimum 5-second delay between fetching the form HTML (and receiving the hash) and submitting the POST. This is implemented in `VichanActions.applyMinimumFetchToSubmitDelay()`.

## TL;DR: 

By enforcing empty fields in Java, mapping out the target thread's dynamically generated garbage keys, preserving exact textarea whitespace, maintaining consistent User-Agent headers, and detecting success via the HTTP redirect chain + `serv` cookie, Clover looks entirely like a standard desktop web client-side browser behavior to Vichan's WAF and can successfully post.

- otacoo
