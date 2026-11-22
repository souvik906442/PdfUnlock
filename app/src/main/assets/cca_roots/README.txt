CCA India root certificates (used to verify digital signatures on Aadhaar, birth certificates, ration cards...).

The GitHub Actions build downloads these from the official site (cca.gov.in) when they are missing:
  CCAIndia2022.cer, CCAIndia2022SPL.cer, CCAIndia2015.cer, CCAIndia2014.cer

If the download is blocked, put the .cer files here yourself (DER or PEM). Official page:
  https://cca.gov.in/root_certificate.html
Compare the SHA-256 fingerprint printed in the build log with the one you get from verifyroot@cca.gov.in.
