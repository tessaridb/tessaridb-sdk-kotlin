package com.tessaridb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The two rules that decide whether a request reaches the server at all (§5.1). */
class HttpPathTest {
    @Test
    fun `the three name segments are checked before they are interpolated`() {
        assertFailsWith<BuilderException> { bucketPath("app", "main", "files/../etc") }
        assertFailsWith<BuilderException> { bucketPath("app", "main; DROP", "assets") }
        assertEquals("/files/app/main/assets", bucketPath("app", "main", "assets"))
    }

    @Test
    fun `a trailing slash names a file and is not a way to list a bucket`() {
        // "list the bucket" and "read the file named /" must not be one
        // keystroke apart, so the bucket route carries no trailing slash at all.
        assertEquals("/files/app/main/assets", bucketPath("app", "main", "assets"))
        assertEquals("/files/app/main/assets/", filePath("app", "main", "assets", ""))
    }

    @Test
    fun `a path is percent-encoded and a slash inside it stays a slash`() {
        // A file may be named anything, and a slash inside the name is part of
        // the name rather than a directory.
        assertEquals(
            "/files/app/main/assets/a/deep/one.txt",
            filePath("app", "main", "assets", "a/deep/one.txt"),
        )
    }

    @Test
    fun `a space is encoded rather than turned into a plus`() {
        // `URLEncoder` is written for form bodies, where `+` means a space. Here
        // it is the character `+`, and an unencoded space makes the request LINE
        // unparseable rather than merely wrong.
        assertEquals("/files/app/main/assets/a%20name.txt", filePath("app", "main", "assets", "a name.txt"))
        assertEquals("/files/app/main/assets/a%2Bb", filePath("app", "main", "assets", "a+b"))
        // And a percent the caller wrote is escaped, rather than asking the
        // server to decode an escape nobody wrote.
        assertEquals("/files/app/main/assets/100%25", filePath("app", "main", "assets", "100%"))
    }

    @Test
    fun `a name outside ASCII travels as its UTF-8 bytes`() {
        assertEquals("/files/app/main/assets/%D0%B4%D0%B0", filePath("app", "main", "assets", "да"))
    }

    @Test
    fun `a session answer without a token is refused rather than guessed at`() {
        assertEquals("abc123", tokenIn("""{"token":"abc123"}"""))
        assertFailsWith<ProtocolException> { tokenIn("""{"session":"abc123"}""") }
        assertFailsWith<ProtocolException> { tokenIn("""{"token":""}""") }
    }
}
