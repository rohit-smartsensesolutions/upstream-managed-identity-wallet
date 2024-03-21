/*
 * *******************************************************************************
 *  Copyright (c) 2021,2024 Contributors to the Eclipse Foundation
 *
 *  See the NOTICE file(s) distributed with this work for additional
 *  information regarding copyright ownership.
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0.
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 *
 *  SPDX-License-Identifier: Apache-2.0
 * ******************************************************************************
 */

package org.eclipse.tractusx.managedidentitywallets.signing;

import com.nimbusds.jose.jwk.KeyType;
import com.nimbusds.jwt.SignedJWT;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.apache.commons.lang3.NotImplementedException;
import org.eclipse.tractusx.managedidentitywallets.constant.StringPool;
import org.eclipse.tractusx.managedidentitywallets.dao.entity.WalletKey;
import org.eclipse.tractusx.managedidentitywallets.domain.CredentialCreationConfig;
import org.eclipse.tractusx.managedidentitywallets.domain.KeyCreationConfig;
import org.eclipse.tractusx.managedidentitywallets.domain.PresentationCreationConfig;
import org.eclipse.tractusx.managedidentitywallets.domain.SigningServiceType;
import org.eclipse.tractusx.managedidentitywallets.domain.VerifiableEncoding;
import org.eclipse.tractusx.ssi.lib.crypt.IKeyGenerator;
import org.eclipse.tractusx.ssi.lib.crypt.KeyPair;
import org.eclipse.tractusx.ssi.lib.crypt.octet.OctetKeyPairFactory;
import org.eclipse.tractusx.ssi.lib.crypt.x25519.X25519Generator;
import org.eclipse.tractusx.ssi.lib.crypt.x25519.X25519PrivateKey;
import org.eclipse.tractusx.ssi.lib.exception.json.TransformJsonLdException;
import org.eclipse.tractusx.ssi.lib.exception.key.InvalidPrivateKeyFormatException;
import org.eclipse.tractusx.ssi.lib.exception.key.KeyGenerationException;
import org.eclipse.tractusx.ssi.lib.exception.proof.SignatureGenerateFailedException;
import org.eclipse.tractusx.ssi.lib.exception.proof.UnsupportedSignatureTypeException;
import org.eclipse.tractusx.ssi.lib.jwt.SignedJwtFactory;
import org.eclipse.tractusx.ssi.lib.model.JsonLdObject;
import org.eclipse.tractusx.ssi.lib.model.proof.Proof;
import org.eclipse.tractusx.ssi.lib.model.proof.jws.JWSSignature2020;
import org.eclipse.tractusx.ssi.lib.model.verifiable.Verifiable;
import org.eclipse.tractusx.ssi.lib.model.verifiable.credential.VerifiableCredentialBuilder;
import org.eclipse.tractusx.ssi.lib.model.verifiable.presentation.VerifiablePresentation;
import org.eclipse.tractusx.ssi.lib.model.verifiable.presentation.VerifiablePresentationBuilder;
import org.eclipse.tractusx.ssi.lib.model.verifiable.presentation.VerifiablePresentationType;
import org.eclipse.tractusx.ssi.lib.proof.LinkedDataProofGenerator;
import org.eclipse.tractusx.ssi.lib.proof.SignatureType;
import org.eclipse.tractusx.ssi.lib.serialization.jsonld.JsonLdSerializerImpl;
import org.eclipse.tractusx.ssi.lib.serialization.jwt.SerializedJwtPresentationFactory;
import org.eclipse.tractusx.ssi.lib.serialization.jwt.SerializedJwtPresentationFactoryImpl;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class LocalSigningService implements SigningService {

    private KeyProvider keyProvider;

    @Override
    public SignerResult createCredential(CredentialCreationConfig config) {
        byte[] privateKeyBytes = keyProvider.getPrivateKey(config.getKeyName());
        VerifiableEncoding encoding = Objects.requireNonNull(config.getEncoding());
        SignerResult.SignerResultBuilder resultBuilder = SignerResult.builder().encoding(encoding);
        switch (encoding) {
            case JSON_LD -> {
                return resultBuilder.jsonLd(createVerifiableCredential(config, privateKeyBytes)).build();
            }
            case JWT -> throw new NotImplementedException("not implemented yet");
            default ->
                    throw new IllegalArgumentException("encoding %s is not supported".formatted(config.getEncoding()));

        }
    }

    @Override

    public KeyPair getKey(KeyCreationConfig config) throws KeyGenerationException {
        KeyType keyType = Objects.requireNonNull(config.getKeyType());
        switch (keyType.getValue().toUpperCase()) {
            case "EC", "RSA" ->
                    throw new NotImplementedException("%s is not implemented yet".formatted(keyType.toString()));
            case "OCT" -> {
                IKeyGenerator keyGenerator = new X25519Generator();
                return keyGenerator.generateKey();
            }
            default -> throw new IllegalArgumentException("%s is not supported".formatted(keyType.toString()));
        }

    }

    @Override
    public SigningServiceType getSupportedServiceType() {
        return SigningServiceType.LOCAL;
    }

    @Override
    public SignerResult createPresentation(PresentationCreationConfig config) {
        byte[] privateKeyBytes = keyProvider.getPrivateKey(config.getKeyName());
        VerifiableEncoding encoding = Objects.requireNonNull(config.getEncoding());
        SignerResult.SignerResultBuilder resultBuilder = SignerResult.builder().encoding(encoding);
        switch (config.getEncoding()) {
            case JWT -> {
                return resultBuilder.jwt(generateJwtPresentation(config, privateKeyBytes).serialize()).build();
            }
            case JSON_LD -> {
                try {
                    return resultBuilder.jsonLd(generateJsonLdPresentation(config, privateKeyBytes)).build();
                } catch (UnsupportedSignatureTypeException | InvalidPrivateKeyFormatException |
                         SignatureGenerateFailedException | TransformJsonLdException e) {
                    throw new IllegalStateException(e);
                }
            }
            default ->
                    throw new IllegalArgumentException("encoding %s is not supported".formatted(config.getEncoding()));
        }
    }

    @Override
    public void setKeyProvider(KeyProvider keyProvider) {
        this.keyProvider = Objects.requireNonNull(keyProvider);
    }

    @Override
    public void saveKey(WalletKey key) {
        keyProvider.saveKeys(key);
    }

    private SignedJWT generateJwtPresentation(PresentationCreationConfig config, byte[] privateKeyBytes) {
        SerializedJwtPresentationFactory presentationFactory = new SerializedJwtPresentationFactoryImpl(
                new SignedJwtFactory(new OctetKeyPairFactory()), new JsonLdSerializerImpl(), config.getVpIssuerDid());

        //Build JWT
        X25519PrivateKey privateKey = null;
        try {
            privateKey = new X25519PrivateKey(privateKeyBytes);
        } catch (InvalidPrivateKeyFormatException e) {
            throw new IllegalArgumentException(e);
        }

        return presentationFactory.createPresentation(config.getVpIssuerDid(), config.getVerifiableCredentials(), config.getAudience(), privateKey, config.getKeyName());
    }

    private VerifiablePresentation generateJsonLdPresentation(PresentationCreationConfig config, byte[] privateKeyBytes) throws UnsupportedSignatureTypeException, InvalidPrivateKeyFormatException, SignatureGenerateFailedException, TransformJsonLdException {
        VerifiablePresentationBuilder verifiablePresentationBuilder =
                new VerifiablePresentationBuilder().id(URI.create(config.getVpIssuerDid() + "#" + UUID.randomUUID().toString()))
                        .type(List.of(VerifiablePresentationType.VERIFIABLE_PRESENTATION))
                        .verifiableCredentials(config.getVerifiableCredentials());


        VerifiablePresentation verifiablePresentation = verifiablePresentationBuilder.build();
        List<String> contexts = verifiablePresentation.getContext().stream().map(URI::toString).collect(Collectors.toList());
        if (!contexts.contains(StringPool.W3_ID_JWS_2020_V1_CONTEXT_URL)) {
            contexts.add(StringPool.W3_ID_JWS_2020_V1_CONTEXT_URL);
        }
        verifiablePresentation.put(JsonLdObject.CONTEXT, contexts);
        LinkedDataProofGenerator generator = LinkedDataProofGenerator.newInstance(SignatureType.JWS);

        X25519PrivateKey privateKey = new X25519PrivateKey(privateKeyBytes);

        Proof proof = generator.createProof(verifiablePresentation, config.getVerificationMethod(),
                privateKey);
        verifiablePresentation.put(Verifiable.PROOF, proof);
        return verifiablePresentation;
    }

    @SneakyThrows
    private static org.eclipse.tractusx.ssi.lib.model.verifiable.credential.VerifiableCredential createVerifiableCredential(CredentialCreationConfig config, byte[] privateKeyBytes) {
        //VC Builder

        // if the credential does not contain the JWS proof-context add it
        URI jwsUri = URI.create("https://w3id.org/security/suites/jws-2020/v1");
        if (!config.getContexts().contains(jwsUri)) {
            config.getContexts().add(jwsUri);
        }

        // check if the expiryDate is set
        // if its null then it will be ignored from the SSI Lib (VerifiableCredentialBuilder) and will not be added to the VC
        Instant expiryInstant = config.getExpiryDate().toInstant();


        URI id = URI.create(UUID.randomUUID().toString());
        VerifiableCredentialBuilder builder = new VerifiableCredentialBuilder()
                .context(config.getContexts())
                .id(URI.create(config.getIssuerDoc().getId() + "#" + id))
                .type(config.getTypes())
                .issuer(config.getIssuerDoc().getId())
                .expirationDate(expiryInstant)
                .issuanceDate(Instant.now())
                .credentialSubject(config.getSubject());


        LinkedDataProofGenerator generator = LinkedDataProofGenerator.newInstance(SignatureType.JWS);
        URI verificationMethod = config.getIssuerDoc().getVerificationMethods().get(0).getId();

        JWSSignature2020 proof =
                (JWSSignature2020) generator.createProof(builder.build(), verificationMethod, new X25519PrivateKey(privateKeyBytes));


        //Adding Proof to VC
        builder.proof(proof);

        //Create Credential
        return builder.build();
    }

}
