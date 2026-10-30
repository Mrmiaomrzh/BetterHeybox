package com.better.heybox;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.max.hbcommon.base.adapter.s;
import com.max.xiaoheihe.bean.bbs.BBSLinkObj;
import com.max.xiaoheihe.bean.news.FeedsContentBaseObj;
import com.max.xiaoheihe.utils.Util;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.List;

public class HeyboxTargetsMatcherTest {


    public static class LinkSinkImpl implements LinkSink<BBSLinkObj> {
        @Override
        public void accept(BBSLinkObj link) {
        }
    }

    public interface LinkSink<T> {
        void accept(T link);
    }

    public static class TypedSourceImpl implements TypedSource<String> {
        @Override
        public List<String> select(String key) {
            return null;
        }
    }

    public interface TypedSource<T> {
        List<T> select(T key);
    }

    private static Method typedBridge() {
        for (Method candidate : TypedSourceImpl.class.getDeclaredMethods()) {
            if (candidate.isBridge()) {
                return candidate;
            }
        }
        throw new AssertionError("夹具前提：TypedSourceImpl 上应有编译器生成的桥接方法");
    }


    public void bindLink(s.HolderSub holder, BBSLinkObj link) {
    }

    public String bindLinkWrongReturn(s.HolderSub holder, BBSLinkObj link) {
        return null;
    }

    public void bindLinkWrongArg(s.HolderSub holder, String notLink) {
    }

    public void bindFeeds(Object holder, FeedsContentBaseObj content) {
    }

    public boolean isVisible(String flag) {
        return true;
    }

    public void applyUtil(Util util) {
    }

    public Object setEnabled(boolean on) {
        return null;
    }

    public void acceptInner(Inner inner) {
    }

    public static class Inner {
    }

    public void bindBigBrother(s.HolderSub holder, int position) {
    }

    public void bindBBDelegate(s.HolderSub holder, s.HolderSub delegate, String tag) {
    }

    public void bindBBDelegateWrong(s.HolderSub holder, Object delegate, String tag) {
    }

    private static Method m(String name, Class<?>... params) {
        try {
            return HeyboxTargetsMatcherTest.class.getDeclaredMethod(name, params);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("测试夹具缺少方法: " + name, e);
        }
    }


    @Test
    public void bbsLinkBinder_matchesExpectedShape() {
        assertTrue(HeyboxTargets.isBbsLinkBinder(m("bindLink", s.HolderSub.class, BBSLinkObj.class)));
    }

    @Test
    public void bbsLinkBinder_rejectsNonVoidReturn() {
        assertFalse(HeyboxTargets.isBbsLinkBinder(
                m("bindLinkWrongReturn", s.HolderSub.class, BBSLinkObj.class)));
    }

    @Test
    public void bbsLinkBinder_rejectsWrongSecondParam() {
        assertFalse(HeyboxTargets.isBbsLinkBinder(
                m("bindLinkWrongArg", s.HolderSub.class, String.class)));
    }

    @Test
    public void bbsLinkBinder_rejectsCompilerGeneratedBridge() throws Exception {
        Method bridge = LinkSinkImpl.class.getDeclaredMethod("accept", Object.class);
        assertTrue("夹具前提：确为编译器生成的桥接方法", bridge.isBridge());

        Method real = LinkSinkImpl.class.getDeclaredMethod("accept", BBSLinkObj.class);
        assertFalse("前提：真实方法非 bridge", real.isBridge());

        assertFalse(HeyboxTargets.isBbsLinkBinder(bridge));
        assertFalse(HeyboxTargets.isBbsLinkBinder(real));
    }


    public List<String> linkList() {
        return null;
    }

    public String linkName() {
        return null;
    }

    @Test
    public void linksGetter_matchesZeroArgListReturn() {
        assertTrue(HeyboxTargets.isLinksGetter(m("linkList")));
        assertFalse(HeyboxTargets.isLinksGetter(m("linkName")));
    }

    @Test
    public void linksGetter_bridgeGuardIsUnreachableForThisShape() {
        assertTrue(typedBridge().isBridge());
        assertFalse(HeyboxTargets.isLinksGetter(typedBridge()));
        assertFalse(m("linkList").isBridge());
    }


    @Test
    public void feedsBinder_matchesBySecondParam() {
        assertTrue(HeyboxTargets.isFeedsBinder(m("bindFeeds", Object.class, FeedsContentBaseObj.class)));
        assertFalse(HeyboxTargets.isFeedsBinder(m("bindLink", s.HolderSub.class, BBSLinkObj.class)));
    }

    @Test
    public void stringPredicate_requiresStringToBoolean() {
        assertTrue(HeyboxTargets.isStringPredicate(m("isVisible", String.class)));
        assertFalse(HeyboxTargets.isStringPredicate(m("bindLinkWrongArg", s.HolderSub.class, String.class)));
    }

    @Test
    public void utilParam_matchesHostUtilPackage() {
        assertTrue(HeyboxTargets.isUtilParam(m("applyUtil", Util.class)));
        assertFalse(HeyboxTargets.isUtilParam(m("bindLinkWrongArg", s.HolderSub.class, String.class)));
    }

    @Test
    public void booleanFlagIn_requiresNonVoidReturn() {
        assertTrue(HeyboxTargets.isBooleanFlagIn(m("setEnabled", boolean.class)));
    }

    @Test
    public void innerParam_matchesOwnNestedClass() {
        assertTrue(HeyboxTargets.isInnerParam(m("acceptInner", Inner.class)));
        assertFalse(HeyboxTargets.isInnerParam(m("applyUtil", Util.class)));
    }

    @Test
    public void viewHolderParam_matchesHostAdapterSubclass() {
        assertTrue(HeyboxTargets.isViewHolderParam(s.HolderSub.class));
        assertFalse(HeyboxTargets.isViewHolderParam(String.class));
        assertFalse(HeyboxTargets.isViewHolderParam(Util.class));
    }

    @Test
    public void bigBrotherBinder_takesIntPosition() {
        assertTrue(HeyboxTargets.isBigBrotherBinder(m("bindBigBrother", s.HolderSub.class, int.class)));
    }

    @Test
    public void bbDelegateBinder_walksSuperclassChain() {
        assertTrue(HeyboxTargets.isBBDelegateBinder(
                m("bindBBDelegate", s.HolderSub.class, s.HolderSub.class, String.class)));
        assertFalse(HeyboxTargets.isBBDelegateBinder(
                m("bindBBDelegateWrong", s.HolderSub.class, Object.class, String.class)));
    }
}
