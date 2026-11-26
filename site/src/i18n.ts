const en = {
  title: 'Helm: Android app for your VPS, Dokploy and Coolify',
  description:
    'Android app and small server agent for your Debian or Ubuntu VPS: health, security, deploys. Works with Dokploy, Coolify or a bare server.',
  ogLocale: 'en_US',
  skip: 'Skip to content',
  switchLang: 'Passer en français',
  langLabel: 'FR',
  themeLabel: 'Toggle dark mode',
  heroTitle: ['Your server,', 'in your pocket.'],
  heroSub: 'Health, security and deploys. From your phone.',
  apk: 'Download APK',
  copy: 'Copy command',
  copied: 'Copied',
  command: 'Install command',
  runOn: 'Run on your server (Debian/Ubuntu, root)',
  notComputer: 'not your computer',
  heroAlt: 'Helm home and Shield screens',
  seeTitle: 'See it',
  seeRegion: 'App screens, scroll sideways',
  screens: [
    { key: 'home', label: 'Home', alt: 'Helm home: server status and resource use' },
    { key: 'apps', label: 'Apps', alt: 'Helm apps list with deploy status' },
    { key: 'shield', label: 'Shield', alt: 'Helm Shield: live probes and bans' },
    { key: 'server', label: 'Server', alt: 'Helm server health: CPU, RAM, disk' },
  ],
  worksTitle: 'Works with',
  works: [
    { name: 'Dokploy', line: 'Apps, deploys, logs.' },
    { name: 'Coolify', line: 'Apps, deploys, logs.' },
    { name: 'Server', line: 'Health, security, updates. Nothing else needed.' },
  ],
  shieldTitle: 'Shield',
  shieldAlt: 'Shield attack map with attempts per country',
  shield: ['Live probes, classified.', 'Ban in one tap. If it looks like you, it asks first.', 'Attack map by country.'],
  installTitle: 'Install in one line',
  steps: [
    { title: 'SSH into your server', line: 'From your computer: ssh root@your-server' },
    { title: 'Install', line: 'Paste the command on the server. Add --domain for https.' },
    { title: 'Pair', line: 'Enter the code in the app.' },
    { title: 'Done', line: 'Your server is in your pocket.' },
  ],
  safeTitle: 'Safe by default',
  safeOffHead: 'Not enabled',
  safeOff: [
    { name: 'SSH country lock', line: 'Opt-in. Your countries are never blocked.', state: 'Off' },
    { name: 'Automatic reboot', line: 'Reboots stay with the app.', state: 'Off' },
    { name: 'Auto-redeploy', line: 'Opt-in, per service.', state: 'Off' },
    { name: 'Public agent port', line: 'Localhost and Docker only.', state: 'Closed' },
  ],
  safeOnHead: 'Enabled',
  safeOn: ['Security patches, daily, no reboot.', 'Vulnerability audit with Trivy, daily.'],
  faqTitle: 'FAQ',
  faq: [
    {
      q: 'What is Helm?',
      a: 'An Android app plus a small agent on your server. It shows server health, security, and your apps and deploys.',
    },
    {
      q: 'Where do I run the install command?',
      a: 'On your server, as root, over SSH: run ssh root@your-server first. The installer refuses any system that is not Debian or Ubuntu, macOS included. Never run it on your laptop.',
    },
    {
      q: 'Does it need Dokploy?',
      a: 'No. It works with Dokploy, Coolify or a bare server; without a platform the Apps tab hides itself. Dokploy and Coolify need their API key or token.',
    },
    {
      q: 'Where does my data go?',
      a: 'From your phone to your own agent, over https. The agent asks ipinfo.io for the country of unknown IPs and downloads ipdeny.com country lists. Set HELM_NO_EXTERNAL_LOOKUPS=1 to stop both.',
    },
    {
      q: 'Is the on-device AI optional?',
      a: 'Yes. The analysis runs on the phone. The optional Gemini Nano summary uses Google ML Kit and stays off until you enable it; ML Kit sends usage diagnostics to Google.',
    },
    {
      q: 'How do I uninstall?',
      a: 'Run the installer with --uninstall. It backs up /etc/helm-agent and /var/lib/helm-agent first.',
    },
    { q: 'Which Android versions?', a: 'Android 10 and newer.' },
  ],
  github: 'GitHub',
  license: 'License:',
  marks: 'Dokploy and Coolify belong to their owners.',
}

export type Dict = typeof en

const fr: Dict = {
  title: 'Helm : app Android pour votre VPS, Dokploy et Coolify',
  description:
    'App Android et petit agent serveur pour votre VPS Debian ou Ubuntu : santé, sécurité, déploiements. Compatible Dokploy, Coolify ou un serveur nu.',
  ogLocale: 'fr_FR',
  skip: 'Aller au contenu',
  switchLang: 'Switch to English',
  langLabel: 'EN',
  themeLabel: 'Basculer le mode sombre',
  heroTitle: ['Votre serveur,', 'dans votre poche.'],
  heroSub: 'Santé, sécurité et déploiements. Depuis votre téléphone.',
  apk: "Télécharger l'APK",
  copy: 'Copier la commande',
  copied: 'Copié',
  command: "Commande d'installation",
  runOn: 'À lancer sur votre serveur (Debian/Ubuntu, root)',
  notComputer: 'pas votre ordinateur',
  heroAlt: 'Écrans Accueil et Shield de Helm',
  seeTitle: 'Voir',
  seeRegion: "Écrans de l'app, défiler sur le côté",
  screens: [
    { key: 'home', label: 'Accueil', alt: 'Accueil Helm : état du serveur et ressources' },
    { key: 'apps', label: 'Apps', alt: 'Liste des apps Helm avec état des déploiements' },
    { key: 'shield', label: 'Shield', alt: 'Shield Helm : sondes en direct et bans' },
    { key: 'server', label: 'Serveur', alt: 'Santé du serveur Helm : CPU, RAM, disque' },
  ],
  worksTitle: 'Compatible avec',
  works: [
    { name: 'Dokploy', line: 'Apps, déploiements, logs.' },
    { name: 'Coolify', line: 'Apps, déploiements, logs.' },
    { name: 'Serveur', line: 'Santé, sécurité, mises à jour. Rien de plus.' },
  ],
  shieldTitle: 'Shield',
  shieldAlt: 'Carte des attaques Shield avec les tentatives par pays',
  shield: ['Sondes en direct, classées.', "Bannir en un geste. Si c'est vous, il demande d'abord.", 'Carte des attaques par pays.'],
  installTitle: 'Installer en une ligne',
  steps: [
    { title: 'Connectez-vous en SSH', line: 'Depuis votre ordinateur : ssh root@votre-serveur' },
    { title: 'Installer', line: 'Collez la commande sur le serveur. Ajoutez --domain pour https.' },
    { title: 'Associer', line: "Saisissez le code dans l'app." },
    { title: 'Terminé', line: 'Votre serveur est dans votre poche.' },
  ],
  safeTitle: 'Sûr par défaut',
  safeOffHead: 'Non activé',
  safeOff: [
    { name: 'Verrou SSH par pays', line: 'Sur option. Vos pays ne sont jamais bloqués.', state: 'Off' },
    { name: 'Redémarrage automatique', line: "Les redémarrages restent dans l'app.", state: 'Off' },
    { name: 'Redéploiement auto', line: 'Sur option, par service.', state: 'Off' },
    { name: "Port public de l'agent", line: 'Localhost et Docker seulement.', state: 'Fermé' },
  ],
  safeOnHead: 'Activé',
  safeOn: ['Correctifs de sécurité, chaque jour, sans redémarrage.', 'Audit de vulnérabilités Trivy, chaque jour.'],
  faqTitle: 'FAQ',
  faq: [
    {
      q: "Qu'est-ce que Helm ?",
      a: "Une app Android et un petit agent sur votre serveur. Elle montre la santé et la sécurité du serveur, vos apps et vos déploiements.",
    },
    {
      q: "Où lancer la commande d'installation ?",
      a: "Sur votre serveur, en root, par SSH : lancez d'abord ssh root@votre-serveur. L'installeur refuse tout système qui n'est pas Debian ou Ubuntu, macOS compris. Ne la lancez jamais sur votre portable.",
    },
    {
      q: 'Faut-il Dokploy ?',
      a: "Non. Compatible Dokploy, Coolify ou un serveur nu ; sans plateforme, l'onglet Apps se masque. Dokploy et Coolify demandent leur clé d'API ou jeton.",
    },
    {
      q: 'Où vont mes données ?',
      a: "De votre téléphone vers votre propre agent, en https. L'agent interroge ipinfo.io pour le pays des IP inconnues et télécharge les listes de pays d'ipdeny.com. HELM_NO_EXTERNAL_LOOKUPS=1 coupe les deux.",
    },
    {
      q: "L'IA embarquée est-elle facultative ?",
      a: "Oui. L'analyse tourne sur le téléphone. Le résumé Gemini Nano, facultatif, utilise Google ML Kit et reste coupé tant que vous ne l'activez pas ; ML Kit envoie des diagnostics d'usage à Google.",
    },
    {
      q: 'Comment désinstaller ?',
      a: "Lancez l'installeur avec --uninstall. Il sauvegarde d'abord /etc/helm-agent et /var/lib/helm-agent.",
    },
    { q: 'Quelles versions d’Android ?', a: 'Android 10 et plus récent.' },
  ],
  github: 'GitHub',
  license: 'Licence :',
  marks: 'Dokploy et Coolify appartiennent à leurs propriétaires.',
}

export const dict = { en, fr }
export type Lang = keyof typeof dict
