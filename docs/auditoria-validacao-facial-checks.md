# Auditoria de Validacao Facial / Checks Visuais

Data: 2026-06-03

Escopo auditado:

- Backend: `FacePhotoProcessor.evaluate`, `PublicFaceValidationController`, `FaceStorageService`.
- Frontend: `CameraCapture`, `faceService`.
- Endpoint publico: `POST /api/public/face/validate`.

Resumo:

- O preview chama `FaceStorageService.validate`, que usa `FacePhotoProcessor.evaluate`.
- O upload final chama `FaceStorageService.store` / `storeBase64`, que usam `FacePhotoProcessor.process`.
- `process` reaproveita `evaluate`; portanto preview e upload final passam pela mesma validacao local.
- A UI agora renderiza apenas checks booleanos presentes no JSON real retornado pelo backend.
- Foi adicionado `eyesVisibleOk`. O wording escolhido e "Olhos visiveis", porque Haar detecta visibilidade dos olhos, nao mede abertura palpebral com garantia absoluta.
- O cascade de olhos foi importado do OpenCV: https://raw.githubusercontent.com/opencv/opencv/4.x/data/haarcascades/haarcascade_eye.xml

| Check exibido na UI | Campo backend | Como e calculado | Validacao real? | Risco de falso positivo | Acao tomada |
| --- | --- | --- | --- | --- | --- |
| Rosto detectado | `faceDetected` | `FaceDetector.detect(rgb)` retorna ao menos uma caixa de rosto. | Sim | Medio: Haar pode errar com pose, luz ruim ou fundo com padroes. | Mantido; UI so mostra OK quando o campo vem `true`. |
| Apenas uma pessoa | `singleFace` | `faceDetected && !secondaryFaceDetected`. | Sim, derivada de deteccoes reais | Medio: rosto secundario muito pequeno/lateral pode escapar. | Mantido; tambem bloqueia aceite no frontend. |
| Nao exibido diretamente | `secondaryFaceDetected` | `hasRelevantSecondaryFace` avalia deteccoes adicionais com IOU, proporcao minima e area relativa. | Sim | Medio: depende da deteccao Haar e dos limiares de relevancia. | Mantido no JSON; usado para mensagem de duas pessoas. |
| Iluminacao | `brightnessOk` | Media da luminancia em escala de cinza dentro dos limites configurados. | Sim | Baixo/medio: media global pode aprovar rosto com sombras localizadas. | Mantido. |
| Nitidez | `sharpnessOk` | Variancia do Laplaciano da imagem em cinza. | Sim | Medio: textura de fundo pode elevar nitidez global. | Mantido. |
| Contraste | `contrastOk` | Desvio-padrao da luminancia. | Sim | Medio: contraste global pode nao representar contraste no rosto. | Mantido. |
| Centralizacao | `centeredOk` | Deslocamento do centro do rosto detectado contra o centro da imagem. | Sim | Baixo/medio: depende da caixa Haar. | Mantido. |
| Tamanho do rosto | `faceSizeOk` / `sizeOk` | Razao minima entre tamanho do rosto detectado e dimensoes da imagem. | Sim | Baixo/medio: depende da caixa Haar. | Mantido; `sizeOk` segue como alias interno/JSON. |
| Rosto visivel | `faceFullyVisibleOk` | Heuristica conservadora de oclusao grossa na parte inferior do rosto, comparando textura inferior vs. superior. | Sim, mas limitada | Alto: nao detecta toda oclusao parcial; antes podia vir `true` sem rosto elegivel. | Ajustado: agora inicia `false` e so vira `true` apos rosto unico, tamanho OK, centralizacao OK e checagem executada. UI label ficou menos absoluto. |
| Olhos visiveis | `eyesVisibleOk` | Cascade Haar de olhos em regiao superior do rosto; exige pelo menos dois olhos plausiveis, separados, nos lados opostos e com baixa diferenca vertical. | Sim | Medio: pode reprovar com oculos, reflexo, sombra, olhos semi-fechados ou pose; pode aprovar se Haar detectar padroes parecidos com olhos. | Adicionado; entra na aprovacao backend, no JSON e no bloqueio frontend. |
| Arquivo final | `finalCompressedSizeOk` | `compressedSizeBytes <= maxAllowedBytes` apos normalizacao/compressao. | Sim | Baixo: calculo direto de bytes. | Mantido. |

Observacoes:

- Nao foi usado o texto "Olhos abertos" como garantia tecnica no check visual, pois a implementacao local valida olhos detectaveis/visiveis, nao abertura real.
- A mensagem de reprovacao backend para olhos e: "Mantenha os olhos abertos e olhando para a câmera."
- O frontend nao aceita a foto quando `eyesVisibleOk !== true`, mesmo que um backend antigo retornasse `approved=true` sem esse campo.
- `nginx` no `docker-compose.prod.yml` usa imagem pronta (`nginx:1.27-alpine`) e nao possui etapa de build propria.
