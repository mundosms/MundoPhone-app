NOTIFY sip:mundokamailio1@172.30.30.40:6171 SIP/2.0
Via: SIP/2.0/UDP 172.30.30.11:5060;branch=z9hG4bKa244.b05f2f45000000000000000000000000.0
To: <sip:mundokamailio1@172.30.30.11>;tag=86fa6dc345
From: <sip:rls@172.30.30.11>;tag=84ef00c017556515b71cbf013ae1c2ee-aae5580a
CSeq: 2 NOTIFY
Call-ID: 66bb98e25149085d
Content-Length: 1899
User-Agent: MundoPBX 2.0 (granja10)
Max-Forwards: 70
Event: presence
Contact: <sip:rls@172.30.30.11>
Subscription-State: active;expires=59
Require: eventlist
Content-Type: multipart/related;type="application/rlmi+xml";start="<1793069801.sip:rls@172.30.30.11.712081031>";boundary="JssDMqKPPwLiVygMYqEZM5nu"

--JssDMqKPPwLiVygMYqEZM5nu
Content-Transfer-Encoding: binary
Content-ID: <1793069801.sip:rls@172.30.30.11.712081031>
Content-Type: application/rlmi+xml;charset="UTF-8"

<?xml version="1.0" encoding="UTF-8"?>
<list uri="sip:rls@172.30.30.11" xmlns="urn:ietf:params:xml:ns:rlmi" version="2" fullState="false"><resource uri="sip:mundokamailio6@172.30.30.11"><instance id="Scf8UhwQ" state="active" cid="1793069801.sip:mundokamailio6@172.30.30.11.309317170"/></resource><resource uri="sip:mundokamailio2@172.30.30.11"><instance id="Scf8UhwQ" state="active" cid="1793069801.sip:mundokamailio2@172.30.30.11.1945124850"/></resource></list>

--JssDMqKPPwLiVygMYqEZM5nu
Content-Transfer-Encoding: binary
Content-ID: <1793069801.sip:mundokamailio6@172.30.30.11.309317170>
Content-Type: application/pidf+xml

<?xml version="1.0" encoding="UTF-8"?>
<presence xmlns="urn:ietf:params:xml:ns:pidf" entity="sip:mundokamailio6@172.30.30.11"><tuple id="tcall"><status><basic>open</basic></status><contact>sip:mundokamailio6@172.30.30.11</contact></tuple>
  <tuple xmlns="urn:ietf:params:xml:ns:pidf" id="0x7f98096f78e0">
    <status>
      <basic>open</basic>
    </status>
  </tuple>
</presence>

--JssDMqKPPwLiVygMYqEZM5nu
Content-Transfer-Encoding: binary
Content-ID: <1793069801.sip:mundokamailio2@172.30.30.11.1945124850>
Content-Type: application/pidf+xml

<?xml version="1.0"?>
<presence xmlns="urn:ietf:params:xml:ns:pidf" xmlns:dm="urn:ietf:params:xml:ns:pidf:data-model" xmlns:rpid="urn:ietf:params:xml:ns:pidf:rpid" xmlns:c="urn:ietf:params:xml:ns:pidf:cipid" entity="sip:mundokamailio2@172.30.30.11">
  <tuple id="0x7f98096f1ba0">
    <status>
      <basic>open</basic>
    </status>
  </tuple>
<tuple xmlns="urn:ietf:params:xml:ns:pidf" id="tcall"><status><basic>open</basic></status><contact>sip:mundokamailio2@172.30.30.11</contact></tuple></presence>

--JssDMqKPPwLiVygMYqEZM5nu--
