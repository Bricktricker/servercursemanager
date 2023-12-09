package bricktricker.servercursemanager.handshake;

public enum PacketType {
    CLIENT_HELLO,
    SERVER_HELLO,
    ENCRYPTED,
    CERTIFICATE,
    CERTIFICATE_VERIFY,
    SERVER_ACCEPTANCE,
    MODPACK_REQUEST,
    MODPACK_RESPONSE,
    ERROR
}
