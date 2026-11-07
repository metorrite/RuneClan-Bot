package com.runeclans.bot.events;

import net.dv8tion.jda.api.entities.Message;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A claim's proof on the hub is the review message's jump link, which lasts, never the attachment's signed CDN address, which expires. */
class EventSubmissionsProofTest {
    @Test
    void theProofLinkIsTheReviewMessagesJumpLink() {
        Message message = mock(Message.class);
        Message.Attachment shot = mock(Message.Attachment.class);
        String jump = "https://discord.com/channels/" + "1".repeat(18) + "/" + "2".repeat(18) + "/" + "3".repeat(18);
        when(message.getAttachments()).thenReturn(List.of(shot));
        when(message.getJumpUrl()).thenReturn(jump);
        assertEquals(jump, EventSubmissions.proofLinkOf(message));
        verify(shot, never()).getUrl();
    }

    @Test
    void aMessageWithoutAPictureHasNoProofLink() {
        Message message = mock(Message.class);
        when(message.getAttachments()).thenReturn(List.of());
        assertNull(EventSubmissions.proofLinkOf(message));
    }
}
