package github.gtopinio.STOMPaaS.services;

import github.gtopinio.STOMPaaS.models.DTOs.SocketDTO;
import github.gtopinio.STOMPaaS.models.classes.SocketMessage;
import github.gtopinio.STOMPaaS.models.enums.MessageType;
import github.gtopinio.STOMPaaS.models.enums.UserType;
import github.gtopinio.STOMPaaS.models.factories.SocketSessionResponseFactory;
import github.gtopinio.STOMPaaS.models.helpers.SocketInputValidator;
import github.gtopinio.STOMPaaS.models.helpers.SocketSessionMapper;
import github.gtopinio.STOMPaaS.models.response.SocketSessionResponse;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Objects;
import java.util.UUID;

@Service
public class SocketService {
    private final SimpMessagingTemplate messagingTemplate;
    private final SocketInputValidator socketInputValidator;
    private final SocketSessionMapper socketSessionMapper;

    public SocketService(
        SimpMessagingTemplate messagingTemplate,
        SocketInputValidator socketInputValidator,
        SocketSessionMapper socketSessionMapper
    ) {
        this.messagingTemplate = messagingTemplate;
        this.socketInputValidator = socketInputValidator;
        this.socketSessionMapper = socketSessionMapper;
    }

    /**
     * This service method is used to link the socket session to the desired socket room.
     *
     * @param input The SocketDTO object containing the socket connection details.
     * @param headerAccessor The SimpMessageHeaderAccessor object containing the message header.
     */
    public SocketSessionResponse linkSocketSession(
        @Payload SocketDTO input,
        SimpMessageHeaderAccessor headerAccessor
    ) {
        // Validate input
        if (!this.socketInputValidator.validate(input)) {
            return SocketSessionResponseFactory.createBadRequestResponse(null, "Invalid input");
        }

        if (!input.getMessageType().equals(MessageType.JOIN)) {
            return SocketSessionResponseFactory.createBadRequestResponse(null, "Invalid message type when linking socket session");
        }

        UUID responseID = this.socketSessionMapper.upsertSocketSession(
            input.getSenderSocketId(),
            input.getOrganizationId(),
            input.getCategories(),
            input.getSocketRoomId(),
            input.getIsForMultipleUsers()
        );

        if (responseID == null) {
            return SocketSessionResponseFactory.createErrorResponse(null, "Error linking socket session");
        }

        var responseMessage = SocketMessage.builder()
                .content("User " + input.getSenderUsername() + " has joined the chat")
                .senderUsername(UserType.SYSTEM.toString())
                .senderSocketId(null)
                .type(MessageType.JOIN)
                .build();

        this.handleJoinMessage(headerAccessor, input.getSenderSocketId(), input.getSocketRoomId(), responseMessage);

        return SocketSessionResponseFactory.createSuccessResponse(responseID, "Socket session linked successfully");
    }

    /**
     * This service method is used to handle the session disconnect event.
     *
     * @param event The SessionDisconnectEvent object containing the session disconnect event details.
     */
    public SocketSessionResponse unlinkSocketSession(SessionDisconnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());

        UUID socketRoomId = UUID.fromString(Objects.requireNonNull(headerAccessor.getSessionAttributes()).get("socketRoomId").toString());
        UUID senderSocketId = UUID.fromString(Objects.requireNonNull(headerAccessor.getSessionAttributes()).get("senderSocketId").toString());

        if (this.socketSessionMapper.removeSocketSession(senderSocketId, socketRoomId)) {
            var responseMessage = SocketMessage.builder()
                    .content("User has left the chat")
                    .senderUsername(UserType.SYSTEM.toString())
                    .senderSocketId(null)
                    .type(MessageType.LEAVE)
                    .build();

            this.broadcastMessage(socketRoomId, responseMessage);
            return SocketSessionResponseFactory.createSuccessResponse(null, "Socket session unlinked successfully");
        }

        return SocketSessionResponseFactory.createErrorResponse(null, "Error unlinking socket session");
    }

    /**
     * This service method is used to handle the JOIN message.
     *
     * @param headerAccessor The SimpMessageHeaderAccessor object containing the message header.
     * @param senderSocketId The UUID of the sender socket.
     * @param socketRoomId The UUID of the socket room.
     * @param message The SocketMessage object containing the message details.
     */
    private void handleJoinMessage(
        SimpMessageHeaderAccessor headerAccessor,
        UUID senderSocketId,
        UUID socketRoomId,
        SocketMessage message
    )
    {
        Objects.requireNonNull(headerAccessor.getSessionAttributes()).put("socketRoomId", socketRoomId);
        headerAccessor.getSessionAttributes().put("senderSocketId", senderSocketId);
        this.broadcastMessage(socketRoomId, message);
    }

    /**
     * This service method is used to broadcast the message to the socket room.
     *
     * @param socketRoomId The UUID of the socket room.
     * @param message The SocketMessage object containing the message details.
     */
    private void broadcastMessage(UUID socketRoomId, SocketMessage message) {
        this.messagingTemplate.convertAndSend("/topic/" + socketRoomId, message);
    }

    /**
     * This service method is used to send a message to the desired socket room.
     *
     * @param input The SocketDTO object containing the socket message details.
     */
    public SocketSessionResponse sendSocketMessage(
        @Payload SocketDTO input
    ) {
        // Validate input
        if (!this.socketInputValidator.validate(input)) {
            return SocketSessionResponseFactory.createBadRequestResponse(null, "Invalid input");
        }

        if (!input.getMessageType().equals(MessageType.MESSAGE)) {
            return SocketSessionResponseFactory.createBadRequestResponse(null, "Invalid message type when sending socket message");
        }

        var responseMessage = SocketMessage.builder()
                .content(input.getSocketMessage().getContent())
                .senderUsername(input.getSenderUsername())
                .senderSocketId(input.getSenderSocketId())
                .type(MessageType.MESSAGE)
                .build();

        this.broadcastMessage(input.getSocketRoomId(), responseMessage);

        return SocketSessionResponseFactory.createSuccessResponse(null, "Socket message sent successfully");
    }

}
