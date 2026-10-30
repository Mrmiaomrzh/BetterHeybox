package com.better.heybox.util

import android.util.Log
import android.view.View
import android.webkit.WebView

object WebViewHider {

    private const val MARK = "data-bhx-hide"

    @JvmStatic
    fun hide(web: View?, rules: BooleanArray?, done: Runnable?): Boolean {
        if (web !is WebView || rules == null || rules.size < 8) {
            done?.run()
            return false
        }
        val script = buildScript(rules[0], rules[5], rules[6], rules[7], rules[3])
        if (script == null) {
            done?.run()
            return false
        }
        evaluate(web, script, done)
        return true
    }

    @JvmStatic
    fun restore(web: View?) {
        if (web !is WebView) {
            return
        }
        evaluate(
            web,
            "(function(){var m=document.querySelectorAll('[$MARK]');"
                    + "for(var i=0;i<m.length;i++){var e=m[i];e.removeAttribute('$MARK');"
                    + "e.style.removeProperty('display');}})();",
            null
        )
    }

    private fun evaluate(web: WebView, script: String, done: Runnable?) {
        var jsEnabled = true
        try {
            jsEnabled = web.settings.javaScriptEnabled
        } catch (ignored: Throwable) {
        }
        Log.i("BetterHeybox", "截图诊断 网页 JS 原状态=$jsEnabled")
        if (!jsEnabled) {
            try {
                web.settings.javaScriptEnabled = true
            } catch (ignored: Throwable) {
            }
        }
        val restoreJs = !jsEnabled
        try {
            web.evaluateJavascript(script) { value ->
                Log.i("BetterHeybox", "截图诊断 网页隐藏结果=$value")
                if (restoreJs) {
                    try {
                        web.settings.javaScriptEnabled = false
                    } catch (ignored: Throwable) {
                    }
                }
                done?.run()
            }
        } catch (throwable: Throwable) {
            Log.i("BetterHeybox", "截图诊断 网页隐藏失败=$throwable")
            if (restoreJs) {
                try {
                    web.settings.javaScriptEnabled = false
                } catch (ignored: Throwable) {
                }
            }
            done?.run()
        }
    }

    private fun buildScript(
        tags: Boolean, follow: Boolean, likeCount: Boolean,
        imageCount: Boolean, irrelevant: Boolean
    ): String? {
        if (!tags && !follow && !likeCount && !imageCount && !irrelevant) {
            return null
        }
        val sb = StringBuilder()
        sb.append("(function(){")
        sb.append("var M='").append(MARK).append("';")
        sb.append("function T(e){return (e.textContent||'').replace(/\\s+/g,'');}")
        sb.append("function S(e){var r=e.getBoundingClientRect();")
            .append("return r.width>0&&r.height>0&&r.width<=460&&r.height<=220;}")
        sb.append("function near(e){var p=e.parentElement;return !!(p&&p.querySelector('svg,img,i'));}")
        sb.append("function mark(e){if(e&&!e.getAttribute(M)){e.setAttribute(M,'1');")
            .append("e.style.setProperty('display','none','important');n++;}}")
        sb.append("function box(e,sel){var c=null;try{c=e.closest(sel);}catch(x){}mark(c||e);}")

        sb.append("function tagRow(e){var k=e.children;if(!k||k.length<2)return false;")
            .append("var cls=k[0].className||'';for(var j=0;j<k.length;j++){var c=k[j];")
            .append("if((c.className||'')!==cls)return false;")
            .append("if(!c.querySelector('img,svg,i'))return false;")
            .append("var s=T(c);if(!s||s.length>12)return false;}return true;}")
        sb.append("var all=document.querySelectorAll('*');")
        sb.append("var n=0;")
        sb.append("for(var i=0;i<all.length;i++){var e=all[i];")

        sb.append("var t=T(e);if(!t||t.length>40)continue;")
        if (tags) {
            sb.append("if(t.charAt(0)==='#'&&t.length<=12&&S(e)){mark(e);continue;}")
            sb.append("if(tagRow(e)){mark(e);continue;}")
        }
        if (follow) {
            sb.append("var f=t.replace(/[+\\uFF0B]/g,'');")
            sb.append("if(f===").append(js("关注")).append("||f===").append(js("已关注"))
                .append("||f===").append(js("关注中")).append("){")
                .append("if(S(e)){mark(e);}else{box(e,'a,span,div');}continue;}")
            sb.append("if(/follow/i.test(e.className||'')&&S(e)){mark(e);continue;}")
        }
        if (imageCount) {
            sb.append("if(/^(").append(re("共")).append("\\d+").append(re("张"))
                .append("|\\d+\\/\\d+)$/.test(t)&&S(e)){mark(e);continue;}")
        }
        if (likeCount) {
            sb.append("if(/^\\d+(\\.\\d+)?[").append(re("万亿"))
                .append("kKwW]?$/.test(t)&&S(e)&&near(e)){mark(e);continue;}")
        }
        if (irrelevant) {
            sb.append("if(/^(").append(re("相关搜索|相关推荐|大家都在搜|都在搜|猜你喜欢|推荐阅读|相关内容"))
                .append(")/.test(t)){")
                .append("box(e,'div,section,ul');continue;}")
            sb.append("if(/^").append(re("合集")).append("/.test(t)&&t.length<=16&&S(e)){")
                .append("mark(e);continue;}")
        }

        sb.append("}return n;})();")
        return sb.toString()
    }

    private fun js(text: String): String = "'" + escape(text) + "'"

    private fun re(text: String): String = escape(text)

    private fun escape(text: String): String {
        val builder = StringBuilder()
        for (element in text) {
            val c = element
            if (c.code < 0x80) {
                builder.append(c)
            } else {
                builder.append("\\u").append(String.format("%04x", c.code))
            }
        }
        return builder.toString()
    }
}
