package org.sagebionetworks.bridge.udd.crypto;

import java.io.IOException;
import java.security.PrivateKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;

import com.google.common.cache.CacheLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.sagebionetworks.bridge.config.Config;
import org.sagebionetworks.bridge.crypto.BcCmsEncryptor;
import org.sagebionetworks.bridge.crypto.CmsEncryptor;
import org.sagebionetworks.bridge.crypto.PemUtils;
import org.sagebionetworks.bridge.udd.s3.S3Helper;

/**
 * This is the cache loader that supports loading CMS encryptors on demand, keyed by the study ID. If the study's
 * encryptor is already in the cache, this returns that encryptor. If it isn't, this study will pull the PEM fils for
 * the cert and private key from the configured S3 bucket and construct an encryptor using those encryption materials.
 */
// TODO: This is copy-pasted and refactored from BridgePF. Refactor this into a shared library.
@Component
public class CmsEncryptorCacheLoader extends CacheLoader<String, CmsEncryptor> {
    private static final String PEM_FILENAME_FORMAT = "%s.pem";

    private Config envConfig;
    private S3Helper s3Helper;

    @Autowired
    public final void setEnvConfig(Config envConfig) {
        this.envConfig = envConfig;
    }

    /** S3 helper, configured by Spring. */
    @Autowired
    public final void setS3Helper(S3Helper s3Helper) {
        this.s3Helper = s3Helper;
    }

    /** {@inheritDoc} */
    @Override
    public CmsEncryptor load(String studyId) throws CertificateEncodingException, IOException {
        String certBucket = envConfig.get("upload.cms.cert.bucket");
        String privKeyBucket = envConfig.get("upload.cms.priv.bucket");
        String pemFileName = String.format(PEM_FILENAME_FORMAT, studyId);

        // download certificate
        String certPem = s3Helper.readS3FileAsString(certBucket, pemFileName);
        X509Certificate cert = PemUtils.loadCertificateFromPem(certPem);

        // download private key
        String privKeyPem = s3Helper.readS3FileAsString(privKeyBucket, pemFileName);
        PrivateKey privKey = PemUtils.loadPrivateKeyFromPem(privKeyPem);

        return new BcCmsEncryptor(cert, privKey);
    }
}
