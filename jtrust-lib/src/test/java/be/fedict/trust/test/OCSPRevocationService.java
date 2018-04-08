/*
 * Java Trust Project.
 * Copyright (C) 2018 e-Contract.be BVBA.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version
 * 3.0 as published by the Free Software Foundation.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, see 
 * http://www.gnu.org/licenses/.
 */

package be.fedict.trust.test;

import java.io.IOException;
import java.io.OutputStream;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.bouncycastle.asn1.x509.AuthorityInformationAccess;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.X509ObjectIdentifiers;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.BasicOCSPRespBuilder;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.CertificateStatus;
import org.bouncycastle.cert.ocsp.OCSPReq;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cert.ocsp.OCSPRespBuilder;
import org.bouncycastle.cert.ocsp.Req;
import org.bouncycastle.cert.ocsp.jcajce.JcaBasicOCSPRespBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DigestCalculatorProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.mortbay.jetty.servlet.ServletHolder;
import org.mortbay.jetty.testing.ServletTester;

public class OCSPRevocationService implements RevocationService {

	private final String identifier;

	private final boolean withOcspResponderCertificate;

	private String ocspUri;

	private CertificationAuthority certificationAuthority;

	private static final Map<String, OCSPRevocationService> ocspRevocationServices;

	private PublicKey ocspResponderPublicKey;
	private PrivateKey ocspResponderPrivateKey;
	private X509Certificate ocspResponderCertificate;

	static {
		ocspRevocationServices = new HashMap<>();
	}

	public OCSPRevocationService() {
		this(false);
	}

	public OCSPRevocationService(boolean withOcspResponderCertificate) {
		this.identifier = UUID.randomUUID().toString();
		this.withOcspResponderCertificate = withOcspResponderCertificate;
		ocspRevocationServices.put(this.identifier, this);
	}

	@Override
	public void addExtension(X509v3CertificateBuilder x509v3CertificateBuilder) throws Exception {
		GeneralName ocspName = new GeneralName(GeneralName.uniformResourceIdentifier, this.ocspUri);
		AuthorityInformationAccess authorityInformationAccess = new AuthorityInformationAccess(
				X509ObjectIdentifiers.ocspAccessMethod, ocspName);
		x509v3CertificateBuilder.addExtension(Extension.authorityInfoAccess, false, authorityInformationAccess);
	}

	@Override
	public void addEndpoints(ServletTester servletTester) {
		String pathSpec = "/" + this.identifier + "/ocsp";
		ServletHolder servletHolder = servletTester.addServlet(OCSPServlet.class, pathSpec);
		servletHolder.setInitParameter("identifier", this.identifier);

	}

	@Override
	public void started(String url) throws Exception {
		this.ocspUri = url + "/" + this.identifier + "/ocsp";
		if (this.withOcspResponderCertificate) {
			KeyPair ocspResponderKeyPair = PKITestUtils.generateKeyPair();
			this.ocspResponderPublicKey = ocspResponderKeyPair.getPublic();
			this.ocspResponderPrivateKey = ocspResponderKeyPair.getPrivate();
			this.ocspResponderCertificate = this.certificationAuthority.issueOCSPResponder(this.ocspResponderPublicKey,
					"CN=OCSP Responder");
		} else {
			this.ocspResponderPublicKey = this.certificationAuthority.getCertificate().getPublicKey();
			this.ocspResponderPrivateKey = this.certificationAuthority.getPrivateKey();
		}
	}

	public static final class OCSPServlet extends HttpServlet {
		private static final Log LOG = LogFactory.getLog(OCSPServlet.class);

		private static final long serialVersionUID = 1L;

		private String identifier;

		@Override
		protected void doPost(HttpServletRequest request, HttpServletResponse response)
				throws ServletException, IOException {
			try {
				_doPost(request, response);
			} catch (Exception e) {
				LOG.error(e);
			}
		}

		private void _doPost(HttpServletRequest request, HttpServletResponse response) throws Exception {
			OCSPRevocationService ocspRevocationService = getOCSPRevocationService();

			byte[] reqData = IOUtils.toByteArray(request.getInputStream());
			OCSPReq ocspReq = new OCSPReq(reqData);

			DigestCalculatorProvider digCalcProv = new JcaDigestCalculatorProviderBuilder()
					.setProvider(BouncyCastleProvider.PROVIDER_NAME).build();
			BasicOCSPRespBuilder basicOCSPRespBuilder = new JcaBasicOCSPRespBuilder(
					ocspRevocationService.ocspResponderPublicKey, digCalcProv.get(CertificateID.HASH_SHA1));

			// request processing
			Req[] requestList = ocspReq.getRequestList();
			for (Req ocspRequest : requestList) {
				CertificateID certificateID = ocspRequest.getCertID();
				CertificateStatus certificateStatus = CertificateStatus.GOOD;
				basicOCSPRespBuilder.addResponse(certificateID, certificateStatus);
			}

			// basic response generation
			X509CertificateHolder[] chain = null;
			if (ocspRevocationService.ocspResponderCertificate != null) {
				chain = new X509CertificateHolder[] {
						new X509CertificateHolder(ocspRevocationService.ocspResponderCertificate.getEncoded()),
						new X509CertificateHolder(
								ocspRevocationService.certificationAuthority.getCertificate().getEncoded()) };
			}

			ContentSigner contentSigner = new JcaContentSignerBuilder("SHA1withRSA")
					.build(ocspRevocationService.ocspResponderPrivateKey);
			BasicOCSPResp basicOCSPResp = basicOCSPRespBuilder.build(contentSigner, chain, new Date());

			// response generation
			OCSPRespBuilder ocspRespBuilder = new OCSPRespBuilder();
			OCSPResp ocspResp = ocspRespBuilder.build(OCSPRespBuilder.SUCCESSFUL, basicOCSPResp);

			response.setContentType("application/ocsp-response");
			OutputStream outputStream = response.getOutputStream();
			IOUtils.write(ocspResp.getEncoded(), outputStream);
		}

		@Override
		public void init(ServletConfig config) throws ServletException {
			this.identifier = config.getInitParameter("identifier");
		}

		private OCSPRevocationService getOCSPRevocationService() {
			return ocspRevocationServices.get(this.identifier);
		}
	}

	@Override
	public void setCertificationAuthority(CertificationAuthority certificationAuthority) {
		this.certificationAuthority = certificationAuthority;
	}
}