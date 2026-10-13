package com.padtools.display;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Exportable ADB RSA identity, encrypted at rest with an Android Keystore AES key. */
final class AdbIdentity {
    final PrivateKey privateKey;
    final Certificate certificate;
    private static final String ALIAS = "PadDisplay.AdbIdentity";

    private AdbIdentity(PrivateKey privateKey, Certificate certificate) {
        this.privateKey = privateKey; this.certificate = certificate;
    }

    static AdbIdentity load(Context context) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        AtomicFile file = new AtomicFile(new File(context.getNoBackupFilesDir(), "adb-identity.bin"));
        if (file.getBaseFile().exists()) {
            SecretKey secret = (SecretKey) store.getKey(ALIAS, null);
            if (secret == null) throw new IllegalStateException("配对密钥已失效，请清除应用数据后重新配对");
            try (DataInputStream in = new DataInputStream(file.openRead())) {
                if (in.readInt() != 1) throw new IllegalStateException("配对密钥格式不正确");
                int ivLength = in.readInt();
                if (ivLength != 12) throw new IllegalStateException("配对密钥格式不正确");
                byte[] iv = new byte[ivLength]; in.readFully(iv);
                ByteArrayOutputStream ciphertext = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) != -1) ciphertext.write(buffer, 0, count);
                byte[] encrypted = ciphertext.toByteArray();
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, secret, new GCMParameterSpec(128, iv));
                try (DataInputStream decoded = new DataInputStream(new ByteArrayInputStream(cipher.doFinal(encrypted)))) {
                    int keyLength = decoded.readInt();
                    if (keyLength < 512 || keyLength > 8192) throw new IllegalStateException("配对密钥格式不正确");
                    byte[] keyBytes = new byte[keyLength]; decoded.readFully(keyBytes);
                    PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
                    Certificate cert = CertificateFactory.getInstance("X.509").generateCertificate(decoded);
                    return new AdbIdentity(key, cert);
                }
            }
        }

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        BouncyCastleProvider provider = new BouncyCastleProvider();
        X500Name name = new X500Name("CN=Pad Display");
        long now = System.currentTimeMillis();
        Certificate cert = new JcaX509CertificateConverter().setProvider(provider).getCertificate(
                new JcaX509v3CertificateBuilder(name, new BigInteger(128, new SecureRandom()),
                        new Date(now - 86400000L), new Date(now + 10L * 365 * 86400000L), name, pair.getPublic())
                        .build(new JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(pair.getPrivate())));

        SecretKey secret;
        if (store.containsAlias(ALIAS)) secret = (SecretKey) store.getKey(ALIAS, null);
        else {
            KeyGenerator aes = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            aes.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build());
            secret = aes.generateKey();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            byte[] key = pair.getPrivate().getEncoded();
            out.writeInt(key.length); out.write(key); out.write(cert.getEncoded());
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secret);
        byte[] encrypted = cipher.doFinal(bytes.toByteArray());
        FileOutputStream saved = file.startWrite();
        try {
            DataOutputStream out = new DataOutputStream(saved);
            out.writeInt(1); out.writeInt(cipher.getIV().length); out.write(cipher.getIV()); out.write(encrypted); out.flush();
            file.finishWrite(saved);
        } catch (Exception e) { file.failWrite(saved); throw e; }
        return new AdbIdentity(pair.getPrivate(), cert);
    }
}
