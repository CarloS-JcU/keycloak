package org.keycloak.social.nia;

import java.net.URI;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import org.jboss.logging.Logger;
import org.keycloak.broker.provider.AbstractIdentityProvider;
import org.keycloak.broker.provider.AuthenticationRequest;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityBrokerException;
import org.keycloak.broker.provider.IdentityProviderDataMarshaller;
import org.keycloak.broker.provider.util.SimpleHttp;
import org.keycloak.broker.social.SocialIdentityProvider;
import org.keycloak.common.util.PemUtils;
import org.keycloak.crypto.KeyStatus;
import org.keycloak.dom.saml.v2.assertion.AssertionType;
import org.keycloak.dom.saml.v2.assertion.AuthnStatementType;
import org.keycloak.dom.saml.v2.assertion.NameIDType;
import org.keycloak.dom.saml.v2.assertion.SubjectType;
import org.keycloak.dom.saml.v2.protocol.AuthnRequestType;
import org.keycloak.dom.saml.v2.protocol.LogoutRequestType;
import org.keycloak.dom.saml.v2.protocol.ResponseType;
import org.keycloak.events.EventBuilder;
import org.keycloak.keys.RsaKeyMetadata;
import org.keycloak.models.FederatedIdentityModel;
import org.keycloak.models.KeyManager;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.protocol.saml.JaxrsSAML2BindingBuilder;
import org.keycloak.protocol.saml.SamlSessionUtils;
import org.keycloak.protocol.saml.preprocessor.SamlAuthenticationPreprocessor;
import org.keycloak.saml.SAML2AuthnRequestBuilder;
import org.keycloak.saml.SAML2LogoutRequestBuilder;
import org.keycloak.saml.SAML2NameIDPolicyBuilder;
import org.keycloak.saml.SAML2RequestedAuthnContextBuilder;
import org.keycloak.saml.SPMetadataDescriptor;
import org.keycloak.saml.SamlProtocolExtensionsAwareBuilder;
import org.keycloak.saml.SignatureAlgorithm;
import org.keycloak.saml.common.constants.GeneralConstants;
import org.keycloak.saml.common.constants.JBossSAMLURIConstants;
import org.keycloak.saml.common.exceptions.ConfigurationException;
import org.keycloak.saml.common.util.DocumentUtil;
import org.keycloak.saml.processing.api.saml.v2.request.SAML2Request;
import org.keycloak.saml.processing.api.saml.v2.sig.SAML2Signature;
import org.keycloak.saml.processing.core.util.KeycloakKeySamlExtensionGenerator;
import org.keycloak.saml.validators.DestinationValidator;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.util.JsonSerialization;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

public class NiaIdentityProvider extends AbstractIdentityProvider<NiaIdentityProviderConfig>
        implements SocialIdentityProvider<NiaIdentityProviderConfig> {

    protected static final Logger logger = Logger.getLogger(NiaIdentityProvider.class);

    private final DestinationValidator destinationValidator;

    public NiaIdentityProvider(KeycloakSession session,
            NiaIdentityProviderConfig config, DestinationValidator destinationValidator) {
        super(session, config);
        this.destinationValidator = destinationValidator;
    }

    @Override
    public Object callback(RealmModel realm, AuthenticationCallback callback, EventBuilder event) {
        return new NiaEndpoint(realm, this, getNiaConfig(), callback, destinationValidator);
    }

    @Override
    public Response performLogin(AuthenticationRequest request) {
        try {
            UriInfo uriInfo = request.getUriInfo();
            RealmModel realm = request.getRealm();
            String issuerURL = getEntityId(uriInfo, realm);
            String destinationUrl = getNiaConfig().getSingleSignOnServiceUrl();
            String nameIDPolicyFormat = getNiaConfig().getNameIDPolicyFormat();

            if (nameIDPolicyFormat == null) {
                nameIDPolicyFormat = JBossSAMLURIConstants.NAMEID_FORMAT_PERSISTENT.get();
            }

            String protocolBinding = JBossSAMLURIConstants.SAML_HTTP_REDIRECT_BINDING.get();

            String assertionConsumerServiceUrl = request.getRedirectUri();

            if (getNiaConfig().isPostBindingResponse()) {
                protocolBinding = JBossSAMLURIConstants.SAML_HTTP_POST_BINDING.get();
            }

            SAML2RequestedAuthnContextBuilder requestedAuthnContext
                    = new SAML2RequestedAuthnContextBuilder()
                            .setComparison(getNiaConfig().getAuthnContextComparisonType());

            for (String authnContextClassRef : getAuthnContextClassRefUris()) {
                requestedAuthnContext.addAuthnContextClassRef(authnContextClassRef);
            }

            for (String authnContextDeclRef : getAuthnContextDeclRefUris()) {
                requestedAuthnContext.addAuthnContextDeclRef(authnContextDeclRef);
            }
            String loginHint = getNiaConfig().isLoginHint() ? request.getAuthenticationSession().getClientNote(OIDCLoginProtocol.LOGIN_HINT_PARAM) : null;
            SAML2AuthnRequestBuilder authnRequestBuilder = new SAML2AuthnRequestBuilder()
                    .assertionConsumerUrl(assertionConsumerServiceUrl)
                    .destination(destinationUrl)
                    .issuer(issuerURL)
                    .forceAuthn(getNiaConfig().isForceAuthn())
                    .protocolBinding(protocolBinding)
                    .nameIdPolicy(SAML2NameIDPolicyBuilder
                            .format(nameIDPolicyFormat)
                            .setAllowCreate(Boolean.TRUE))
                    .requestedAuthnContext(requestedAuthnContext)
                    .subject(loginHint)
                    .addExtension(new NiaSPType())
                    .addExtension(new NiaCustomAttributes());

            JaxrsSAML2BindingBuilder binding = new JaxrsSAML2BindingBuilder(session)
                    .relayState(request.getState().getEncoded());
            boolean postBinding = getNiaConfig().isPostBindingAuthnRequest();

            if (getNiaConfig().isWantAuthnRequestsSigned()) {
                KeyManager.ActiveRsaKey keys = session.keys().getActiveRsaKey(realm);

                String keyName = getNiaConfig().getXmlSigKeyInfoKeyNameTransformer().getKeyName(keys.getKid(), keys.getCertificate());
                binding.signWith(keyName, keys.getPrivateKey(), keys.getPublicKey(), keys.getCertificate())
                        .signatureAlgorithm(getSignatureAlgorithm())
                        .signDocument();
                if (!postBinding && getNiaConfig().isAddExtensionsElementWithKeyInfo()) {    // Only include extension if REDIRECT binding and signing whole SAML protocol message
                    authnRequestBuilder.addExtension(new KeycloakKeySamlExtensionGenerator(keyName));
                }
            }

            AuthnRequestType authnRequest = authnRequestBuilder.createAuthnRequest();
            for (Iterator<SamlAuthenticationPreprocessor> it = SamlSessionUtils.getSamlAuthenticationPreprocessorIterator(session); it.hasNext();) {
                authnRequest = it.next().beforeSendingLoginRequest(authnRequest, request.getAuthenticationSession());
            }

            if (authnRequest.getDestination() != null) {
                destinationUrl = authnRequest.getDestination().toString();
            }

            if (postBinding) {
                return binding.postBinding(authnRequestBuilder.toDocument()).request(destinationUrl);
            } else {
                return binding.redirectBinding(authnRequestBuilder.toDocument()).request(destinationUrl);
            }
        } catch (Exception e) {
            throw new IdentityBrokerException("Could not create authentication request.", e);
        }
    }

    private String getEntityId(UriInfo uriInfo, RealmModel realm) {
        String configEntityId = getNiaConfig().getEntityId();

        if (configEntityId == null || configEntityId.isEmpty()) {
            return UriBuilder.fromUri(uriInfo.getBaseUri()).path("realms").path(realm.getName()).build().toString();
        } else {
            return configEntityId;
        }
        //   return UriBuilder.fromUri(uriInfo.getBaseUri()).path("realms").path(realm.getName()).build().toString();
    }

    private List<String> getAuthnContextClassRefUris() {
        String authnContextClassRefs = getNiaConfig().getAuthnContextClassRefs();
        if (authnContextClassRefs == null || authnContextClassRefs.isEmpty()) {
            return new LinkedList<String>();
        }

        try {
            return Arrays.asList(JsonSerialization.readValue(authnContextClassRefs, String[].class));
        } catch (Exception e) {
            logger.warn("Could not json-deserialize AuthContextClassRefs config entry: " + authnContextClassRefs, e);
            return new LinkedList<String>();
        }
    }

    private List<String> getAuthnContextDeclRefUris() {
        String authnContextDeclRefs = getNiaConfig().getAuthnContextDeclRefs();
        if (authnContextDeclRefs == null || authnContextDeclRefs.isEmpty()) {
            return new LinkedList<String>();
        }

        try {
            return Arrays.asList(JsonSerialization.readValue(authnContextDeclRefs, String[].class));
        } catch (Exception e) {
            logger.warn("Could not json-deserialize AuthContextDeclRefs config entry: " + authnContextDeclRefs, e);
            return new LinkedList<String>();
        }
    }

    public SignatureAlgorithm getSignatureAlgorithm() {
        String alg = getNiaConfig().getSignatureAlgorithm();
        if (alg != null) {
            SignatureAlgorithm algorithm = SignatureAlgorithm.valueOf(alg);
            if (algorithm != null) {
                return algorithm;
            }
        }
        return SignatureAlgorithm.RSA_SHA256;
    }

    @Override
    public void authenticationFinished(AuthenticationSessionModel authSession, BrokeredIdentityContext context) {
        ResponseType responseType = (ResponseType) context.getContextData().get(NiaEndpoint.SAML_LOGIN_RESPONSE);
        AssertionType assertion = (AssertionType) context.getContextData().get(NiaEndpoint.SAML_ASSERTION);
        SubjectType subject = assertion.getSubject();
        SubjectType.STSubType subType = subject.getSubType();
        if (subType != null) {
            NameIDType subjectNameID = (NameIDType) subType.getBaseID();
            authSession.setUserSessionNote(NiaEndpoint.SAML_FEDERATED_SUBJECT_NAMEID, subjectNameID.serializeAsString());
        }
        AuthnStatementType authn = (AuthnStatementType) context.getContextData().get(NiaEndpoint.SAML_AUTHN_STATEMENT);
        if (authn != null && authn.getSessionIndex() != null) {
            authSession.setUserSessionNote(NiaEndpoint.SAML_FEDERATED_SESSION_INDEX, authn.getSessionIndex());

        }
    }

    @Override
    public void backchannelLogout(KeycloakSession session, UserSessionModel userSession, UriInfo uriInfo, RealmModel realm) {
        String singleLogoutServiceUrl = getNiaConfig().getSingleLogoutServiceUrl();
        if (singleLogoutServiceUrl == null || singleLogoutServiceUrl.trim().equals("") || !getNiaConfig().isBackchannelSupported()) {
            return;
        }
        JaxrsSAML2BindingBuilder binding = buildLogoutBinding(session, userSession, realm);
        try {
            LogoutRequestType logoutRequest = buildLogoutRequest(userSession, uriInfo, realm, singleLogoutServiceUrl);
            if (logoutRequest.getDestination() != null) {
                singleLogoutServiceUrl = logoutRequest.getDestination().toString();
            }
            int status = SimpleHttp.doPost(singleLogoutServiceUrl, session)
                    .param(GeneralConstants.SAML_REQUEST_KEY, binding.postBinding(SAML2Request.convert(logoutRequest)).encoded())
                    .param(GeneralConstants.RELAY_STATE, userSession.getId()).asStatus();
            boolean success = status >= 200 && status < 400;
            if (!success) {
                logger.warn("Failed saml backchannel broker logout to: " + singleLogoutServiceUrl);
            }
        } catch (Exception e) {
            logger.warn("Failed saml backchannel broker logout to: " + singleLogoutServiceUrl, e);
        }

    }

    @Override
    public Response keycloakInitiatedBrowserLogout(KeycloakSession session, UserSessionModel userSession, UriInfo uriInfo, RealmModel realm) {
        String singleLogoutServiceUrl = getNiaConfig().getSingleLogoutServiceUrl();
        if (singleLogoutServiceUrl == null || singleLogoutServiceUrl.trim().equals("")) {
            return null;
        }

        if (getNiaConfig().isBackchannelSupported()) {
            backchannelLogout(session, userSession, uriInfo, realm);
            return null;
        } else {
            try {
                LogoutRequestType logoutRequest = buildLogoutRequest(userSession, uriInfo, realm, singleLogoutServiceUrl);
                if (logoutRequest.getDestination() != null) {
                    singleLogoutServiceUrl = logoutRequest.getDestination().toString();
                }
                JaxrsSAML2BindingBuilder binding = buildLogoutBinding(session, userSession, realm);
                if (getNiaConfig().isPostBindingLogout()) {
                    return binding.postBinding(SAML2Request.convert(logoutRequest)).request(singleLogoutServiceUrl);
                } else {
                    return binding.redirectBinding(SAML2Request.convert(logoutRequest)).request(singleLogoutServiceUrl);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    protected LogoutRequestType buildLogoutRequest(UserSessionModel userSession, UriInfo uriInfo, RealmModel realm, String singleLogoutServiceUrl, SamlProtocolExtensionsAwareBuilder.NodeGenerator... extensions) throws ConfigurationException {
        SAML2LogoutRequestBuilder logoutBuilder = new SAML2LogoutRequestBuilder()
                .assertionExpiration(realm.getAccessCodeLifespan())
                .issuer(getEntityId(uriInfo, realm))
                .sessionIndex(userSession.getNote(NiaEndpoint.SAML_FEDERATED_SESSION_INDEX))
                .nameId(NameIDType.deserializeFromString(userSession.getNote(NiaEndpoint.SAML_FEDERATED_SUBJECT_NAMEID)))
                .destination(singleLogoutServiceUrl);
        LogoutRequestType logoutRequest = logoutBuilder.createLogoutRequest();
        for (SamlProtocolExtensionsAwareBuilder.NodeGenerator extension : extensions) {
            logoutBuilder.addExtension(extension);
        }
        for (Iterator<SamlAuthenticationPreprocessor> it = SamlSessionUtils.getSamlAuthenticationPreprocessorIterator(session); it.hasNext();) {
            logoutRequest = it.next().beforeSendingLogoutRequest(logoutRequest, userSession, null);
        }
        return logoutRequest;
    }

    private JaxrsSAML2BindingBuilder buildLogoutBinding(KeycloakSession session, UserSessionModel userSession, RealmModel realm) {
        JaxrsSAML2BindingBuilder binding = new JaxrsSAML2BindingBuilder(session)
                .relayState(userSession.getId());
        if (getNiaConfig().isWantAuthnRequestsSigned()) {
            KeyManager.ActiveRsaKey keys = session.keys().getActiveRsaKey(realm);
            String keyName = getNiaConfig().getXmlSigKeyInfoKeyNameTransformer().getKeyName(keys.getKid(), keys.getCertificate());
            binding.signWith(keyName, keys.getPrivateKey(), keys.getPublicKey(), keys.getCertificate())
                    .signatureAlgorithm(getSignatureAlgorithm())
                    .signDocument();
        }
        return binding;
    }

    @Override
    public Response export(UriInfo uriInfo, RealmModel realm, String format) {
        try {
            URI authnBinding = JBossSAMLURIConstants.SAML_HTTP_REDIRECT_BINDING.getUri();

            if (getNiaConfig().isPostBindingAuthnRequest()) {
                authnBinding = JBossSAMLURIConstants.SAML_HTTP_POST_BINDING.getUri();
            }

            URI endpoint = uriInfo.getBaseUriBuilder()
                    .path("realms").path(realm.getName())
                    .path("broker")
                    .path(getNiaConfig().getAlias())
                    .path("endpoint")
                    .build();

            boolean wantAuthnRequestsSigned = getNiaConfig().isWantAuthnRequestsSigned();
            boolean wantAssertionsSigned = getNiaConfig().isWantAssertionsSigned();
            boolean wantAssertionsEncrypted = getNiaConfig().isWantAssertionsEncrypted();
            String entityId = getEntityId(uriInfo, realm);
            String nameIDPolicyFormat = getNiaConfig().getNameIDPolicyFormat();

            List<Element> signingKeys = new ArrayList<Element>();
            List<Element> encryptionKeys = new ArrayList<Element>();

            Set<RsaKeyMetadata> keys = new TreeSet<>((o1, o2) -> o1.getStatus() == o2.getStatus() // Status can be only PASSIVE OR ACTIVE, push PASSIVE to end of list
                    ? (int) (o2.getProviderPriority() - o1.getProviderPriority())
                    : (o1.getStatus() == KeyStatus.PASSIVE ? 1 : -1));
            keys.addAll(session.keys().getRsaKeys(realm));
            for (RsaKeyMetadata key : keys) {
                if (key == null || key.getCertificate() == null) {
                    continue;
                }

                signingKeys.add(SPMetadataDescriptor.buildKeyInfoElement(key.getKid(), PemUtils.encodeCertificate(key.getCertificate())));

                if (key.getStatus() == KeyStatus.ACTIVE) {
                    encryptionKeys.add(SPMetadataDescriptor.buildKeyInfoElement(key.getKid(), PemUtils.encodeCertificate(key.getCertificate())));
                }
            }
            String descriptor = SPMetadataDescriptor.getSPDescriptor(authnBinding, endpoint, endpoint,
                    wantAuthnRequestsSigned, wantAssertionsSigned, wantAssertionsEncrypted,
                    entityId, nameIDPolicyFormat, signingKeys, encryptionKeys);

            // Metadata signing
            if (getNiaConfig().isSignSpMetadata()) {
                KeyManager.ActiveRsaKey activeKey = session.keys().getActiveRsaKey(realm);
                String keyName = getNiaConfig().getXmlSigKeyInfoKeyNameTransformer().getKeyName(activeKey.getKid(), activeKey.getCertificate());
                KeyPair keyPair = new KeyPair(activeKey.getPublicKey(), activeKey.getPrivateKey());

                Document metadataDocument = DocumentUtil.getDocument(descriptor);
                SAML2Signature signatureHelper = new SAML2Signature();
                signatureHelper.setSignatureMethod(getSignatureAlgorithm().getXmlSignatureMethod());
                signatureHelper.setDigestMethod(getSignatureAlgorithm().getXmlSignatureDigestMethod());

                Node nextSibling = metadataDocument.getDocumentElement().getFirstChild();
                signatureHelper.setNextSibling(nextSibling);

                signatureHelper.signSAMLDocument(metadataDocument, keyName, keyPair, CanonicalizationMethod.EXCLUSIVE);

                descriptor = DocumentUtil.getDocumentAsString(metadataDocument);
            }

            return Response.ok(descriptor, MediaType.APPLICATION_XML_TYPE).build();
        } catch (Exception e) {
            logger.warn("Failed to export SAML SP Metadata!", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public Response retrieveToken(KeycloakSession session, FederatedIdentityModel identity) {
        return Response.ok(identity.getToken()).build();
    }

    public NiaIdentityProviderConfig getNiaConfig() {
        NiaIdentityProviderConfig niaIdentityProviderConfig = new NiaIdentityProviderConfig();
        return niaIdentityProviderConfig;
        //return (NiaIdentityProviderConfig) super.getConfig();

    }

    @Override
    public IdentityProviderDataMarshaller getMarshaller() {
        return new NiaDataMarshaller();
    }
}
