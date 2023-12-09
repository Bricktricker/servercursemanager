# Protocol

ServerCurseManager uses a custom protocol to encrypt and authenticate the client to the server and to downlaod the modpack. The protocol is inspired by TLS 1.3, but uses a different certificate format. Because cryptography is hard and you should never do your own crpyt, please don't trust the protocol to much. But it was a fun project for me and there are no private information transfered, this shouldn't be a problem.

## Mojang Certificate
In modern Minecraft every player has a RSA key pair and a certificate issued by mojang. This certificate consist of the players UUID, the players public key, and the expire date. These 3 values get concatinated and are signed with the Mojang private key using the `SHA256withRSA` algorithm.

## General Packet Layout

Every packet starts with the ASCII values "SCM1", followed by an unsigned 32bit integer indicating the length of the packet, without the "SCM1" and length bytes. After that 1 byte follows indicating the type of packet, and the the packet payload follows. Encrypted packets have a packet type of `ENCRYPTED`, and their actual packet type is the first byte in the decrypted payload.

## Packet Types
- CLIENT_HELLO	(C -> S)
- SERVER_HELLO (S -> C)
- ENCRYPTED (S -> C, C -> S)
- CERTIFICATE (C -> S)
- CERTIFICATE_VERIFY (C -> S)
- SERVER_ACCEPTANCE (S -> C)
- MODPACK_REQUEST (C -> S)
- MODPACK_RESPONSE (S -> C)
- ERROR (S -> C)

## The Protocol

1. The Client generates a X25519 key pair `ckp` and 32 random bytes `cRand`.
2. The cleint send a CLIENT_HELLO packet to the server, containing the public key from `ckp` and the 32 random bytes `cRand`.
3. The server generates a X25519 key pair `skp` and 32 random bytes `sRand`.
4. The server responds with a SERVER_HELLO packet containing the public key from `skp` and the 32 random bytes `sRand`.
5. The client and the server derive the shared key data:
	 1. Both parties compute the shared secret `sh` from the received public key and their own private key.
	 2. A key expansion is done on `sh` with [HKDF](https://datatracker.ietf.org/doc/html/rfc5869) just like [TLS1.3](https://datatracker.ietf.org/doc/html/rfc8446#section-7.1).
	 3. With the key expansion the client/server write keys and the cleint/server IVs are computed.
	 4. The write keys are 256Bit AES keys and the IVs are 12 bytes long.
6. All messages are now encrypted, except for the ERROR packet
7. The client send a CERTIFICATE packet to the server, containing the client UUID, its public key, the expire date and the mojang signature.
8. The cleint hashes all send and received messages and signs the hash value with its private key. The client send a CERTIFICATE_VERIFY packet to the server, containing the signature.
9. The Server validates the received certificate and certificate signature. The server sends a SERVER_ACCEPTANCE packet, containing a boolean whether the client is allowed to request the modpack
10. The cleint sends a MODPACK_REQUEST paket to the server containing the hash of the current modpack.zip file
11. The server checks if the send hash differs from the current modpack version and responds with a MODPACK_RESPONSE packet
12. The connection gets closed.
