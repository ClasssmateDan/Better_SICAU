package com.dan.qrbatch.network;

import org.junit.Test;
import static org.junit.Assert.*;

/** Local strings only, including markup that must never execute or cause resource requests. */
public class ProfileNamesTest {
    @Test public void legacyInputAndUnquotedReorderedAttributes() {
        assertEquals("测试甲", ProfileNames.fromHtml("<input value=测试甲 disabled id=messageName>"));
        assertEquals("测试乙", ProfileNames.fromHtml("<INPUT NAME='messageName' VALUE='测试乙'>"));
    }
    @Test public void regularTextElementIsSupported() {
        assertEquals("测试姓名", ProfileNames.fromHtml("<span id='messageName'>测试姓名</span>"));
    }
    @Test public void titleSuppliesUntruncatedName() {
        assertEquals("完整测试姓名", ProfileNames.fromHtml("<p id='messageName' title='完整测试姓名'>完整测…</p>"));
        assertEquals("测试姓名", ProfileNames.fromHtml("<span id='messageName' title='测试姓名'></span>"));
    }
    @Test public void nestedTextAndNumericEntitiesAreDecoded() {
        assertEquals("测试姓名", ProfileNames.fromHtml("<div id='messageName'><b>&#x6D4B;&#35797;</b>姓名</div>"));
        assertEquals("A & B", ProfileNames.fromHtml("<input id='messageName' value='A &amp; B'>"));
    }
    @Test public void tooltipCannotReplaceActualText() {
        assertEquals("测试姓名", ProfileNames.fromHtml("<span id='messageName' title='点击修改姓名'>测试姓名</span>"));
    }
    @Test public void recognisedRealNameFieldsAreSupported() {
        assertEquals("测试甲", ProfileNames.fromHtml("<input id='realName' value='测试甲'>"));
        assertEquals("测试乙", ProfileNames.fromHtml("<span name='realname'>测试乙</span>"));
    }
    @Test public void commentsScriptsAndUnrelatedNamesAreIgnored() {
        String html = "<!-- <input id='messageName' value='错误姓名'> -->"
                + "<script>var s=\"<input id='messageName' value='错误姓名'>\";</script>"
                + "<input id='nickname' value='昵称'><input name='name' value='其他人的姓名'>"
                + "<img src='https://example.invalid/not-requested'>";
        assertEquals("", ProfileNames.fromHtml(html));
        assertEquals("", ProfileNames.fromHtml("<script id='messageName'>错误姓名</script>"));
    }
    @Test public void maskedOrPlaceholderNamesAreNotInvented() {
        assertEquals("", ProfileNames.fromHtml("<span id='messageName'>张**</span>"));
        assertEquals("", ProfileNames.fromHtml("<input id='messageName' value='请输入姓名'>"));
        assertEquals("", ProfileNames.fromHtml("<p>资料页格式已变化</p>"));
    }
    @Test public void manuallySuppliedNamesMustBeCompletePlainText() {
        assertEquals("测试姓名", ProfileNames.clean(" 测试姓名 "));
        for (String invalid : new String[]{"null", "undefined", "<b>姓名</b>", "{{name}}", "${name}", "张**", "name\u0000", "https://example.invalid"})
            assertEquals("", ProfileNames.clean(invalid));
    }
}
