package com.better.heybox.util;

import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebView;

public final class WebViewHider {

    private static final String MARK = "data-bhx-hide";

    private WebViewHider() {
    }

    public static boolean hide(View web, boolean[] rules, final Runnable done) {
        if (!(web instanceof WebView) || rules == null || rules.length < 8) {
            if (done != null) {
                done.run();
            }
            return false;
        }
        String script = buildScript(rules[0], rules[5], rules[6], rules[7], rules[3]);
        if (script == null) {
            if (done != null) {
                done.run();
            }
            return false;
        }
        evaluate((WebView) web, script, done);
        return true;
    }

    public static void restore(View web) {
        if (!(web instanceof WebView)) {
            return;
        }
        evaluate((WebView) web,
                "(function(){var m=document.querySelectorAll('[" + MARK + "]');"
                        + "for(var i=0;i<m.length;i++){var e=m[i];e.removeAttribute('" + MARK + "');"
                        + "e.style.removeProperty('display');}})();",
                null);
    }

    private static void evaluate(final WebView web, String script, final Runnable done) {
        boolean jsEnabled = true;
        try {
            jsEnabled = web.getSettings().getJavaScriptEnabled();
        } catch (Throwable ignored) {

        }
        android.util.Log.i("BetterHeybox", "截图诊断 网页 JS 原状态=" + jsEnabled);
        if (!jsEnabled) {
            try {

                web.getSettings().setJavaScriptEnabled(true);
            } catch (Throwable ignored) {

            }
        }
        final boolean restoreJs = !jsEnabled;
        try {
            web.evaluateJavascript(script, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    android.util.Log.i("BetterHeybox", "截图诊断 网页隐藏结果=" + value);
                    if (restoreJs) {
                        try {
                            web.getSettings().setJavaScriptEnabled(false);
                        } catch (Throwable ignored) {

                        }
                    }
                    if (done != null) {
                        done.run();
                    }
                }
            });
        } catch (Throwable throwable) {
            android.util.Log.i("BetterHeybox", "截图诊断 网页隐藏失败=" + throwable);
            if (restoreJs) {
                try {
                    web.getSettings().setJavaScriptEnabled(false);
                } catch (Throwable ignored) {

                }
            }
            if (done != null) {
                done.run();
            }
        }
    }

    private static String buildScript(boolean tags, boolean follow, boolean likeCount,
            boolean imageCount, boolean irrelevant) {
        if (!tags && !follow && !likeCount && !imageCount && !irrelevant) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("(function(){");
        sb.append("var M='").append(MARK).append("';");
        sb.append("function T(e){return (e.textContent||'').replace(/\\s+/g,'');}");
        sb.append("function S(e){var r=e.getBoundingClientRect();")
                .append("return r.width>0&&r.height>0&&r.width<=460&&r.height<=220;}");
        sb.append("function near(e){var p=e.parentElement;return !!(p&&p.querySelector('svg,img,i'));}");
        sb.append("function mark(e){if(e&&!e.getAttribute(M)){e.setAttribute(M,'1');")
                .append("e.style.setProperty('display','none','important');n++;}}");
        sb.append("function box(e,sel){var c=null;try{c=e.closest(sel);}catch(x){}mark(c||e);}");

        sb.append("function tagRow(e){var k=e.children;if(!k||k.length<2)return false;")
                .append("var cls=k[0].className||'';for(var j=0;j<k.length;j++){var c=k[j];")
                .append("if((c.className||'')!==cls)return false;")
                .append("if(!c.querySelector('img,svg,i'))return false;")
                .append("var s=T(c);if(!s||s.length>12)return false;}return true;}");
        sb.append("var all=document.querySelectorAll('*');");
        sb.append("var n=0;");
        sb.append("for(var i=0;i<all.length;i++){var e=all[i];");

        sb.append("var t=T(e);if(!t||t.length>40)continue;");
        if (tags) {
            sb.append("if(t.charAt(0)==='#'&&t.length<=12&&S(e)){mark(e);continue;}");
            sb.append("if(tagRow(e)){mark(e);continue;}");
        }
        if (follow) {

            sb.append("var f=t.replace(/[+\\uFF0B]/g,'');");
            sb.append("if(f===").append(js("\u5173\u6ce8")).append("||f===").append(js("\u5df2\u5173\u6ce8"))
                    .append("||f===").append(js("\u5173\u6ce8\u4e2d")).append("){")
                    .append("if(S(e)){mark(e);}else{box(e,'a,span,div');}continue;}");
            sb.append("if(/follow/i.test(e.className||'')&&S(e)){mark(e);continue;}");
        }
        if (imageCount) {
            sb.append("if(/^(").append(re("\u5171")).append("\\d+").append(re("\u5f20"))
                    .append("|\\d+\\/\\d+)$/.test(t)&&S(e)){mark(e);continue;}");
        }
        if (likeCount) {
            sb.append("if(/^\\d+(\\.\\d+)?[").append(re("\u4e07\u4ebf"))
                    .append("kKwW]?$/.test(t)&&S(e)&&near(e)){mark(e);continue;}");
        }
        if (irrelevant) {
            sb.append("if(/^(").append(re("\u76f8\u5173\u641c\u7d22|\u76f8\u5173\u63a8\u8350|\u5927\u5bb6\u90fd\u5728\u641c|\u90fd\u5728\u641c|\u731c\u4f60\u559c\u6b22|\u63a8\u8350\u9605\u8bfb|\u76f8\u5173\u5185\u5bb9"))
                    .append(")/.test(t)){")
                    .append("box(e,'div,section,ul');continue;}");

            sb.append("if(/^").append(re("\u5408\u96c6")).append("/.test(t)&&t.length<=16&&S(e)){")
                    .append("mark(e);continue;}");
        }

        sb.append("}return n;})();");
        return sb.toString();
    }

    private static String js(String text) {
        return "'" + escape(text) + "'";
    }

    private static String re(String text) {
        return escape(text);
    }

    private static String escape(String text) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                builder.append(c);
            } else {
                builder.append("\\u").append(String.format("%04x", (int) c));
            }
        }
        return builder.toString();
    }
}
