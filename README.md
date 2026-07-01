# GBN-UDP — Go-Back-N em Java sobre UDP

Implementação do protocolo de transferência confiável **Go-Back-N (GBN)** em Java puro (somente JDK), utilizando as primitivas `DatagramSocket` e `DatagramPacket`, estritamente alinhado às Máquinas de Estados Finitas (FSM) descritas no livro *Redes de Computadores e a Internet: Uma Abordagem Top-Down* (Kurose & Ross, 8ª ed., Cap. 3).

---

## 📁 Estrutura de Pastas

    Protocolo_Go-Back-N-main/
    ├── .github/
    │   └── workflows/
    │       ├── ci.yml               -> CI: build + testes + cobertura JaCoCo a cada push/PR
    │       ├── pr-validation.yml    -> Validação automática de Pull Requests
    │       ├── docker-publish.yml   -> Publica imagem Docker no GHCR a cada push/tag
    │       └── release.yml          -> Cria GitHub Release com o jar ao criar tag v*
    ├── gbn-udp/
    │   ├── src/
    │   │   ├── main/java/gbn/
    │   │   │   ├── Packet.java      -> Estrutura binária, serialização e desserialização via ByteBuffer
    │   │   │   ├── Receptor.java    -> FSM do Receptor (controle de ordem, descarte estocástico e I/O)
    │   │   │   └── Emissor.java     -> FSM do Emissor (janela deslizante N, threads assíncronas e timer único)
    │   │   └── test/java/gbn/
    │   │       ├── PacketTest.java  -> Testes unitários de serialização/desserialização
    │   │       └── EmissorTest.java -> Testes unitários de segmentação de arquivo
    │   ├── pom.xml                  -> Maven: dependências (JUnit 5) e plug-in JaCoCo
    │   └── Dockerfile               -> Build multi-stage para execução em container isolado
    ├── LICENSE
    ├── README
    └── Relatorio.pdf

---

## 🛠️ Decisões de Projeto e Suposições Assumidas

1. **Janela Deslizante e Sequenciamento Absoluto:** Optou-se pela utilização de identificadores numéricos do tipo `int` de 32 bits crescentes. Para a volumetria de arquivos exigida, essa abordagem evita a necessidade de tratamento de estouro de escopo (*wrap around*) por aritmética modular, mantendo o foco do código na corretude do pipeline do protocolo.
2. **Parametrização da Porta de Destino:** Visto que o enunciado sugere o formato estático `IP_destino:path_destino`, convencionou-se o uso da porta UDP padrão **5000** para escuta do Receptor. Caso necessário, o Emissor aceita opcionalmente um 5º argumento em linha de comando para sobrescrever esse valor.
3. **Mecanismo Multithreading no Emissor:** Para viabilizar o envio em lote sem bloqueio por ACKs individuais, o Emissor gerencia duas threads concorrentes sincronizadas por exclusão mútua (`synchronized`): uma thread de envio associada à janela ativa e uma thread monitora dedicada exclusivamente à captura de ACKs no socket.
4. **Verificação de Integridade via MD5 fim-a-fim:** O Emissor calcula o hash MD5 (`java.security.MessageDigest`) do arquivo original antes da transmissão e o envia embutido no payload do pacote HANDSHAKE (formato `path|tamanho|probPerda|md5Origem`). O Receptor recalcula o MD5 de forma incremental (`digest.update()`) a cada segmento gravado com sucesso e, ao final da transferência, compara o hash resultante com o `md5Origem` recebido, reportando `OK` ou `FALHA` no console — sem precisar reler o arquivo do disco.

---

## 🚀 Compilação e Construção

O projeto requer **Java 17+** e **Maven** instalados localmente. Navegue até a pasta `gbn-udp` onde o `pom.xml` se encontra:

```powershell
cd gbn-udp
mvn clean package
```

Isso compilará o código, executará as suítes de teste e gerará o executável em `target/gbn-udp.jar`.

> **Sem Maven?** Compile diretamente com `javac`:
> ```powershell
> mkdir out
> javac -d out src/main/java/gbn/*.java
> ```
> Nos comandos abaixo, substitua `-cp target/gbn-udp.jar` por `-cp out`.

---

## 💻 Instruções de Execução (Windows)

### Passo 1: Criar arquivo de teste (2 MB)

Abra o PowerShell dentro da pasta `gbn-udp` e execute:

```powershell
$bytes = New-Object byte[] (2 * 1024 * 1024)
(New-Object Random).NextBytes($bytes)
[IO.File]::WriteAllBytes("teste.bin", $bytes)
```

### Passo 2: Criar a pasta de destino

```powershell
mkdir C:\temp
```

### Passo 3: Iniciar o Receptor

Abra um **primeiro terminal PowerShell** dentro da pasta `gbn-udp` e execute:

```powershell
java -cp target/gbn-udp.jar gbn.Receptor 5000
```

O Receptor ficará aguardando conexões na porta 5000. **Mantenha esse terminal aberto.**

### Passo 4: Iniciar o Emissor

Abra um **segundo terminal PowerShell** na mesma pasta e execute:

**Envio estável — sem perdas simuladas, Janela N=8:**

```powershell
java -cp target/gbn-udp.jar gbn.Emissor teste.bin 127.0.0.1:C:recebido.bin 8 0.0
```

**Envio sob estresse — 10% de perda simulada, Janela N=4:**

```powershell
java -cp target/gbn-udp.jar gbn.Emissor teste.bin 127.0.0.1:C:recebido_N4.bin 4 0.10
```

Você verá mensagens de `Timeout! Retransmitindo...` no terminal do Emissor — isso é o GBN funcionando corretamente. Ao final, ambos os terminais exibem estatísticas de pacotes enviados, retransmissões, ACKs recebidos e taxa de perda efetiva.

### Passo 5: Verificar integridade

A verificação de integridade é **automática**: o Receptor calcula o MD5 do arquivo gravado e o compara com o MD5 do arquivo original (recebido no HANDSHAKE), exibindo o resultado ao final da transferência:

```
[Receptor] MD5 esperado (do arquivo original): 3b1e...
[Receptor] MD5 do arquivo recebido: 3b1e...
[Receptor] Verificação de integridade: OK (hashes idênticos)
```

Se quiser conferir manualmente também, ainda é possível usar:

```powershell
Get-FileHash .\teste.bin -Algorithm MD5
Get-FileHash .\recebido.bin -Algorithm MD5
```

As cadeias hexadecimais devem ser perfeitamente idênticas.

---

## 🧪 Relatório de Cobertura e Testes

```powershell
mvn test
```

O plug-in **JaCoCo** gera a análise de cobertura em `target/site/jacoco/index.html`. Abra esse arquivo no navegador para visualizar o relatório.

---

## 🐳 Docker

```powershell
docker build -t gbn-udp .
docker run --rm -p 5000:5000/udp gbn-udp java -cp gbn-udp.jar gbn.Receptor 5000
```

---

## ⚙️ Integração Contínua (GitHub Actions)

O repositório conta com quatro pipelines em `.github/workflows/`. A cada `push` ou Pull Request para `main`, um agente virtual instala o JDK 17, executa `mvn package` e atesta a integridade de todos os testes unitários. Ao criar uma tag `v*`, o pipeline de release publica automaticamente o jar como GitHub Release e a imagem Docker no GHCR.

---

## 👤 Autoria

- **Instituição:** Universidade Federal de Alfenas (UNIFAL-MG)
- **Disciplina:** Redes de Computadores
- **Docente:** Prof. Flavio Barbieri Gonzaga
- **Discente:** Bianca Dyandra Ribeiro Gomes de Farias
