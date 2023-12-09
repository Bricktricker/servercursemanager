package bricktricker.servercursemanager.handshake;

import java.security.PublicKey;
import java.util.UUID;

public class ServerHandshakeData extends HandshakeData {
    
    private UUID playerUUID;
    
    private PublicKey playerPublicKey;
    
    private ValidationStatus validationStatus = ValidationStatus.NONE;

    public UUID getPlayerUUID() {
        if(this.playerUUID == null) {
            throw new IllegalStateException("Player UUID not set");
        }
        return playerUUID;
    }

    public void setPlayerUUID(UUID playerUUID) {
        this.playerUUID = playerUUID;
    }

    public PublicKey getPlayerPublicKey() {
        if(this.playerPublicKey == null) {
            throw new IllegalStateException("Player public key not set");
        }
        return playerPublicKey;
    }

    public void setPlayerPublicKey(PublicKey playerPublicKey) {
        this.playerPublicKey = playerPublicKey;
    }

    public ValidationStatus getValidationStatus() {
        return validationStatus;
    }

    public void setValidationStatus(ValidationStatus validationStatus) {
        this.validationStatus = validationStatus;
    }

    public enum ValidationStatus {
        NONE,
        REJECTED,
        VALID_CERT,
        ACCEPTED
    }

}
