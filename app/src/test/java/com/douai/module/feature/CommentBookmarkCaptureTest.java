package com.douai.module.feature;
import static org.junit.Assert.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE)
public class CommentBookmarkCaptureTest {
 public static class User {public String getUid(){return "91";}public String getNickname(){return "作者甲";}}
 public static class Comment {
  public String cid="31",awemeId="21",text="评论",rootCommentId="30",replyToReplyId="29",replyToUserId="81",replyToUserName="作者乙",replyToReplyCommentText="已下发正文";
  public User user=new User();
 }
 @Test public void capturesOnlyPresentContextAndSurrogateSafeExcerpt(){
  Comment c=new Comment();c.text="中".repeat(1199)+"😀";
  CommentBookmarkStore.Entry e=CommentBookmarks.capture(c,1234);
  assertEquals("21",e.aid);assertEquals("31",e.cid);assertEquals("91",e.authorUid);assertEquals("作者甲",e.author);
  assertEquals("30",e.rootCid);assertEquals("29",e.replyCid);assertEquals("81",e.replyUid);assertEquals("作者乙",e.replyAuthor);assertEquals("已下发正文",e.replyText);
  assertEquals(1199,e.text.length());assertTrue(e.truncated);assertEquals("",e.asr);assertEquals(1234,e.savedAt);
  c.replyToReplyCommentText=null;assertEquals("",CommentBookmarks.capture(c,1).replyText);
  assertNull(CommentBookmarks.capture(null,1));c.cid="";assertNull(CommentBookmarks.capture(c,1));
 }
}
