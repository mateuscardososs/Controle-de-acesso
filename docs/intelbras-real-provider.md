# Intelbras Real Provider

## Escopo

Integração real para controladoras Intelbras SS 5531 MF W usando:

- CGI com Digest Auth para status, configuração, usuários, faces, snapshot e recordFinder.
- RPC2 HTTP mantido para diagnóstico e firmwares que exponham chamadas RPC compatíveis.
- Provider fake preservado como padrão de desenvolvimento.

Device real validado:

- IP: `192.168.15.5`
- HTTP: `80`
- Modelo: `SS 5531 MF W`
- Serial: `DRWL3903457HU`
- API: HTTP CGI Digest para cadastro real de usuário/face

## Configuração

Modo padrão:

```properties
app.intelbras.mode=fake
```

Modo real:

```properties
app.intelbras.mode=real
app.intelbras.default-username=admin
app.intelbras.default-password=<senha>
app.intelbras.connection-timeout=3s
app.intelbras.read-timeout=5s
```

As credenciais podem vir do device. Se `intelbrasUsername` ou `intelbrasPassword` não forem informados no cadastro do device, o provider usa os defaults globais.

Exemplo de cadastro do device real via API:

```bash
curl -X POST http://localhost:8080/api/devices \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Intelbras SS 5531 Portaria",
    "model": "Intelbras SS 5531 MF W",
    "serialNumber": "DRWL3903457HU",
    "ipAddress": "192.168.15.5",
    "httpPort": 80,
    "intelbrasUsername": "admin",
    "intelbrasPassword": "<senha>",
    "location": "Portaria",
    "operationType": "ENTRY_EXIT",
    "status": "ONLINE",
    "areaId": "<areaId>"
  }'
```

`DeviceResponse` nunca retorna a senha; ele expõe apenas `intelbrasPasswordConfigured`.

### Cadastro via interface

Na tela `/devices`, clique em **Novo dispositivo** e use **Preencher modelo Intelbras SS 5531** para aplicar os valores sugeridos do modelo real. Para a controladora validada, preencha:

- Nome: `Intelbras Portaria`
- Modelo: `Intelbras SS 5531 MF W`
- Serial: `DRWL3903457HU`
- IP: `192.168.15.5`
- Porta HTTP: `80`
- Usuário: `admin`
- Senha: senha real configurada na controladora
- Área: `Portaria`
- Localização: `Portaria principal`
- Operação: `Entrada/Saída`
- Status: `Online`

A senha é enviada somente no cadastro e não aparece na listagem; a interface mostra apenas se as credenciais estão configuradas.

## Login RPC2

Endpoint:

```text
POST http://192.168.15.5/RPC2
```

Primeira chamada, sem senha:

```bash
curl -s http://192.168.15.5/RPC2 \
  -H "Content-Type: application/json" \
  -d '{
    "method": "global.login",
    "params": {
      "userName": "admin",
      "password": "",
      "clientType": "Web3.0"
    },
    "id": 1
  }'
```

A resposta traz `realm`, `random` e `session`. O client calcula:

```text
pwd1 = MD5(username:realm:password).upper()
pwd2 = MD5(username:random:pwd1).upper()
```

Segunda chamada:

```bash
curl -s http://192.168.15.5/RPC2 \
  -H "Content-Type: application/json" \
  -d '{
    "method": "global.login",
    "params": {
      "userName": "admin",
      "password": "<pwd2>",
      "clientType": "Web3.0",
      "authorityType": "Default"
    },
    "session": "<session>",
    "id": 2
  }'
```

Keep alive:

```json
{
  "method": "global.keepAlive",
  "params": { "timeout": 300, "active": true },
  "session": "<session>",
  "id": 3
}
```

## Fluxo Real de Usuário/Face

O `IntelbrasRealProvider.syncPerson` faz:

1. Resolve devices Intelbras cadastrados.
2. Usa credencial por device ou default global.
3. Gera identificadores compatíveis com SS 5531 MF W / SS 5541 MF W:
   - Estratégia padrão `document`: `UserID` e `CardNo` usam CPF/documento apenas com números, preservando zero à esquerda.
   - Sem CPF/documento, o fallback usa identificador numérico curto derivado do UUID.
   - Estratégias alternativas: `short_numeric` e `short_alphanumeric`.
4. Converte a imagem local para JPEG Base64, normalizando para os limites da linha Bio-T. Se a foto falhar, o cadastro do usuário continua sem face.
5. Consulta usuário por `recordFinder.cgi?action=find&name=AccessControlCard&condition.UserID=<id>`.
6. Cria ou atualiza usuário por `recordUpdater.cgi?action=insert|update&name=AccessControlCard`.
7. Remove face anterior por `FaceInfoManager.cgi?action=remove&UserID=<id>`, se existir.
8. Cadastra face por `FaceInfoManager.cgi?action=add`. Se a face for rejeitada, o usuário/cartão permanece sincronizado.

Endpoints reais usados para cadastro:

```text
GET  /cgi-bin/recordFinder.cgi?action=find&name=AccessControlCard&condition.UserID=<UserID>
GET  /cgi-bin/recordUpdater.cgi?action=insert&name=AccessControlCard&CardNo=<CardNo>&CardStatus=0&CardName=<nome>&UserID=<UserID>
GET  /cgi-bin/recordUpdater.cgi?action=update&name=AccessControlCard&recno=<RecNo>&...
GET  /cgi-bin/FaceInfoManager.cgi?action=remove&UserID=<UserID>
POST /cgi-bin/FaceInfoManager.cgi?action=add
```

Payload base usado para face em `FaceInfoManager.cgi?action=add`:

```json
{
  "UserID": "05731650411",
  "Info": {
    "UserName": "Nome da Pessoa",
    "PhotoData": ["<jpeg-base64>"]
  }
}
```

Auditoria de compatibilidade:

- A SS 5531 MF W responde `magicBox.cgi` e `recordFinder.cgi` via CGI Digest.
- A documentação oficial de integração da linha Bio-T descreve cadastro de usuário por `recordUpdater.cgi` e cadastro de foto/face por `FaceInfoManager.cgi`.
- Para `SS 5531 MF W` / `SS 5541 MF W`, o exemplo oficial de `Create New User` inclui `CardStatus=0`; alguns firmwares retornam `HTTP 400 Bad Request` quando esse campo é omitido.
- O fluxo anterior via `/RPC2` com `AccessUser.insertMulti` e `AccessFace.insertMulti` é incompatível com a controladora validada quando o erro aparece como `Intelbras RPC request failed`.
- `APP_INTELBRAS_IDENTITY_STRATEGY=document` é o padrão recomendado para SS 5531/SS 5541 quando a controladora já contém registros com CPF em `UserID` e `CardNo`.
- `short_alphanumeric` gera `UserID` com prefixo (`G...`/`E...`) e deve ser evitado em firmwares que rejeitam `recordUpdater.cgi` com `HTTP 400`.
- `Doors[0]`, `TimeSections[0]`, `CardType`, `CardStatus`, `IsValid`, `ValidDateStart` e `ValidDateEnd` foram removidos do payload padrão porque firmwares SS 5531/SS 5541 diferentes aceitam subconjuntos distintos desses campos. Quando incompatíveis, o firmware costuma responder apenas `HTTP 400` com `Error Bad Request!`.
- `PhotoData` é enviado como array de Base64 JPEG. A imagem é normalizada para respeitar os limites documentados: máximo `600x1200`, altura até duas vezes a largura e até `100KB`.
- Logs operacionais:
  - `intelbras_request`: URL final, query params completos, digest e payload HTTP.
  - `intelbras_response`: status e body completo retornado pela controladora.
  - `intelbras_card_payload`: payload `AccessControlCard` antes do envio.
  - `intelbras_facial_payload`: payload facial com `PhotoData` mascarado por tamanho/hash.
  - `intelbras_payload_invalid`: campos conhecidos como problemáticos detectados antes do envio.
  - `intelbras_device_rejected_payload`: rejeição HTTP ou rejeição textual no body.
  - `intelbras_identity_strategy`: estratégia usada para gerar `UserID` e `CardNo`.

## Compatibilidade SS 5531 / SS 5541

A integração real foi ajustada para a família Bio-T usada por `SS 5531 MF W` e `SS 5541 MF W`. A documentação web oficial desses modelos indica CGI como protocolo habilitável na interface web, mas os firmwares publicados para a linha SS 55xx têm diferenças entre Interface Web 1.0 e 2.0. Na prática, `recordUpdater.cgi` aceita melhor um cadastro mínimo de `AccessControlCard` e rejeita silenciosamente combinações de parâmetros opcionais.

Payload mínimo usado:

```text
action=insert
name=AccessControlCard
CardNo=<numero>
CardStatus=0
CardName=<nome>
UserID=<id>
```

Com `APP_INTELBRAS_IDENTITY_STRATEGY=document` e CPF disponível:

```text
action=insert
name=AccessControlCard
CardNo=05731650411
CardStatus=0
CardName=mateus da silva cardoso
UserID=05731650411
```

Para update:

```text
action=update
name=AccessControlCard
recno=<RecNo retornado pelo recordFinder>
CardNo=<numero>
CardStatus=0
CardName=<nome>
UserID=<id>
```

Limitações do `recordUpdater.cgi`:

- O erro `Error Bad Request!` não informa o parâmetro rejeitado.
- `AccessControlCardRec` é registro de eventos/acessos e deve ser usado em `recordFinder.cgi`; o cadastro/alteração de usuário/cartão deve permanecer em `AccessControlCard`.
- `update` depende de `recno`; se o `recordFinder` indica usuário existente sem `RecNo`, o client evita inserir duplicado.
- O fallback automático tenta `update` depois de `insert` apenas quando uma nova consulta encontra `RecNo`.
- Se o `GET` documentado for rejeitado pelo firmware, o client registra a rejeição e tenta o mesmo payload via `POST application/x-www-form-urlencoded`; se ainda falhar, tenta a variante legada do ZIP homologado com `CardType=0`, `IsValid=true`, `Doors[0]=0`, `TimeSections[0]=255`, `ValidDateStart` e `ValidDateEnd`.
- Validade por datas, portas e faixas horárias deve ser tratada com cautela por firmware. O payload padrão deixa a política de acesso do usuário sob a configuração padrão do dispositivo.

## CGI Digest

Endpoints implementados:

```text
/cgi-bin/magicBox.cgi?action=getDeviceType
/cgi-bin/magicBox.cgi?action=getSerialNo
/cgi-bin/magicBox.cgi?action=getSoftwareVersion
/cgi-bin/global.cgi?action=getCurrentTime
/cgi-bin/configManager.cgi?action=getConfig&name=Network
/cgi-bin/recordFinder.cgi?action=find&name=AccessControlCard
/cgi-bin/recordFinder.cgi?action=find&name=AccessControlCardRec
/cgi-bin/recordUpdater.cgi?action=insert&name=AccessControlCard
/cgi-bin/recordUpdater.cgi?action=update&name=AccessControlCard
/cgi-bin/recordUpdater.cgi?action=remove&name=AccessControlCard
/cgi-bin/FaceInfoManager.cgi?action=add
/cgi-bin/FaceInfoManager.cgi?action=remove
/cgi-bin/snapshot.cgi?channel=1
```

Exemplo:

```bash
curl --digest -u "admin:<senha>" \
  "http://192.168.15.5/cgi-bin/magicBox.cgi?action=getSerialNo"
```

Snapshot:

```bash
curl --digest -u "admin:<senha>" \
  "http://192.168.15.5/cgi-bin/snapshot.cgi?channel=1" \
  -o snapshot.jpg
```

## Eventos Reais

Eventos confirmados:

```text
/cgi-bin/recordFinder.cgi?action=find&name=AccessControlCardRec
```

Exemplo:

```bash
curl --digest -u "admin:<senha>" \
  "http://192.168.15.5/cgi-bin/recordFinder.cgi?action=find&name=AccessControlCardRec"
```

Endpoint administrativo para importar e publicar realtime:

```bash
curl -X POST http://localhost:8080/api/admin/intelbras/devices/<deviceId>/events/import \
  -H "Authorization: Bearer $TOKEN"
```

Endpoint para importar todos os dispositivos Intelbras online:

```bash
curl -X POST http://localhost:8080/api/admin/intelbras/events/import \
  -H "Authorization: Bearer $TOKEN"
```

O polling automático usa:

```env
APP_INTELBRAS_EVENTS_POLLING_ENABLED=true
APP_INTELBRAS_EVENTS_POLLING_INTERVAL=5s
```

O import salva em `access_events`, faz dedupe por `deviceId + RecNo` quando `RecNo` existir, ou por `deviceId + personId + eventTime + origin` como fallback, registra auditoria e publica no WebSocket `/topic/access-events`.

## Mapeamento AccessControlCardRec

Campos preservados no `rawPayload`:

- `CardName`
- `UserID`
- `RecNo`
- `Status`
- `Method`
- `Type`
- `CreateTime`
- `URL`
- `ErrorCode`
- `Door`
- `ReaderID`

Normalização:

- `Status=1` e `ErrorCode=0` viram `ALLOWED`.
- `Status=0` ou erro viram `DENIED`/`ERROR`.
- `ReaderID=2` ou `Type=Exit` vira `EXIT`.
- Demais eventos permitidos viram `ENTRY`.
- Eventos negados viram `ACCESS_DENIED`.

O import tenta resolver pessoa por CPF em colaboradores e visitantes. Se não encontrar, usa um UUID determinístico a partir do identificador externo para manter o evento persistível.

## Segurança

- Senha nunca é logada.
- Erros mascaram IPv4 como `192.168.*.*`.
- `DeviceResponse` não expõe senha.
- Timeouts curtos são configuráveis.
- RPC/CGI fazem retry controlado de uma repetição em falhas transitórias de I/O.

## Limitações Conhecidas

- `AccessUser.insertMulti`, `AccessFace.removeMulti` e `AccessUser.removeMulti` podem variar por firmware. O provider registra falhas sem expor senha.
- `ProviderPermission` atual não carrega documento/UserID, então o provider real aceita a alteração e aplica validade no próximo `syncPerson`.
- A importação de eventos depende de `recordFinder.cgi?action=find&name=AccessControlCardRec` estar disponível no firmware.
- Não há criptografia dedicada para `intelbras_password` em banco; use default por ambiente quando isso for preferível operacionalmente.
