package com.better.heybox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.better.heybox.hooks.ShareLinkPurifyHook;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public class ShareLinkPurifyHookTest {

    private static String purify(String url) throws Exception {
        Constructor<ShareLinkPurifyHook> ctor =
                ShareLinkPurifyHook.class.getDeclaredConstructor(MainModule.class);
        ctor.setAccessible(true);
        ShareLinkPurifyHook hook = ctor.newInstance(new Object[]{null});
        Method m = ShareLinkPurifyHook.class.getDeclaredMethod("purify", String.class);
        m.setAccessible(true);
        return (String) m.invoke(hook, url);
    }


    @Test
    public void nonHttpSchemeIsUntouched() throws Exception {
        String url = "xiaoheihe.cn/bbs?id=1&sid=abc";
        assertEquals(url, purify(url));
    }

    @Test
    public void thirdPartyHostIsUntouched() throws Exception {
        String url = "https://example.com/a?link_id=1&sid=abc";
        assertEquals(url, purify(url));
    }

    @Test
    public void nullStaysNull() throws Exception {
        assertNull(purify(null));
    }

    @Test
    public void urlWithoutQueryIsUntouched() throws Exception {
        String url = "https://bbs.xiaoheihe.cn/web/share";
        assertEquals(url, purify(url));
    }


    @Test
    public void apexAndSubdomainAreBothPurged() throws Exception {
        assertEquals("https://xiaoheihe.cn/a?link_id=1",
                purify("https://xiaoheihe.cn/a?link_id=1&sid=x"));
        assertEquals("https://bbs.xiaoheihe.cn/a?link_id=1",
                purify("https://bbs.xiaoheihe.cn/a?link_id=1&sid=x"));
    }

    @Test
    public void lookalikeHostIsNotPurged() throws Exception {
        String url = "https://evilxiaoheihe.cn/a?link_id=1&sid=x";
        assertEquals(url, purify(url));
    }


    @Test
    public void blacklistedParamsAreRemoved() throws Exception {
        assertEquals("https://xiaoheihe.cn/a?link_id=1&from=feed",
                purify("https://xiaoheihe.cn/a?link_id=1&sid=abc&from=feed&heybox_id=99"));
    }

    @Test
    public void utmParamsAreRemoved() throws Exception {
        assertEquals("https://xiaoheihe.cn/a?link_id=1",
                purify("https://xiaoheihe.cn/a?utm_source=w&link_id=1&utm_medium=c"));
    }

    @Test
    public void paramNameMatchIsCaseInsensitive() throws Exception {
        assertEquals("https://xiaoheihe.cn/a?link_id=1",
                purify("https://xiaoheihe.cn/a?link_id=1&SID=abc"));
    }

    @Test
    public void untouchedWhenNoBlacklistHit() throws Exception {
        String url = "https://xiaoheihe.cn/a?link_id=1&custom=%E4%B8%AD%E6%96%87";
        assertEquals(url, purify(url));
    }

    @Test
    public void danglingQuestionMarkIsDropped() throws Exception {
        assertEquals("https://xiaoheihe.cn/a", purify("https://xiaoheihe.cn/a?sid=abc"));
    }


    @Test
    public void fragmentIsPreserved() throws Exception {
        assertEquals("https://xiaoheihe.cn/a?link_id=1#section",
                purify("https://xiaoheihe.cn/a?link_id=1&sid=abc#section"));
    }

    @Test
    public void fragmentIsNotPurified() throws Exception {
        assertEquals("https://xiaoheihe.cn/a#sid=keep",
                purify("https://xiaoheihe.cn/a?sid=drop#sid=keep"));
    }

    @Test
    public void percentEncodedParamNameIsDecodedBeforeMatch() throws Exception {
        assertEquals("https://xiaoheihe.cn/a?link_id=1",
                purify("https://xiaoheihe.cn/a?link_id=1&%73id=abc"));
    }

    @Test
    public void keptParamsAreNotReencoded() throws Exception {
        assertEquals("https://xiaoheihe.cn/a?title=%E4%B8%AD%E6%96%87",
                purify("https://xiaoheihe.cn/a?title=%E4%B8%AD%E6%96%87&sid=x"));
    }
}