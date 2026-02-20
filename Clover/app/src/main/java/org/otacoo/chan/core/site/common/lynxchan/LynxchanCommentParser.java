package org.otacoo.chan.core.site.common.lynxchan;

import org.otacoo.chan.core.site.parser.CommentParser;
import org.otacoo.chan.core.site.parser.StyleRule;

import java.util.regex.Pattern;

public class LynxchanCommentParser extends CommentParser {
    public LynxchanCommentParser() {
        addDefaultRules();
        
        // Lynxchan quotes usually look like >>123456 or /[board]/res/[thread].html#[post]
        setQuotePattern(Pattern.compile(".*#(\\d+)"));
        setFullQuotePattern(Pattern.compile("/(\\w+)/res/(\\d+)\\.html#(\\d+)"));

        // Lynxchan specific HTML tags if any
        rule(StyleRule.tagRule("span").cssClass("quote").color(StyleRule.Color.QUOTE).linkify());
        rule(StyleRule.tagRule("span").cssClass("greenText").color(StyleRule.Color.QUOTE));
    }
}
