#!/usr/bin/env bash
# 헷갈리는 말 모델을 가르칠 글을 받아 둔다. 인자: 받을 디렉터리 (기본 ./corpus).
#
# 이 환경에서는 raw.githubusercontent.com 만 열려 있다(위키·허깅페이스는 막힘).
# 격식체(청원·번역문)만으로는 "카카오가 나을까" 같은 입말에 약해서, 영화평·댓글·챗봇 같은
# 입말 글을 같이 받는다. 합쳐서 약 900MB, 받는 데 몇 분이다.
set -euo pipefail
dir="${1:-corpus}"
mkdir -p "$dir"
cd "$dir"

get() { # 이름 주소
  [ -s "$1" ] && return 0
  curl -fsSL --retry 3 -o "$1.part" "$2" && mv "$1.part" "$1"
}

# 청와대 국민청원 (lovit/petitions_archive) — 달마다 한 파일. 없는 달은 건너뛴다.
for m in 2017-08 2017-09 2017-10 2017-11 2017-12 2018-01 2018-02 2018-03 2018-04 2018-05 2018-06 \
         2018-07 2018-08 2018-09 2018-10 2018-11 2018-12 2019-01 2019-02 2019-03 2019-04 2019-05 \
         2019-06 2019-07 2019-08; do
  get "pet_$m" "https://raw.githubusercontent.com/lovit/petitions_archive/master/petitions_$m" || rm -f "pet_$m.part"
done

R=https://raw.githubusercontent.com
# 입말: 영화평, 챗봇 문답, 인터넷 댓글, 짧은 대화문
get raw_nsmc_train.txt   $R/e9t/nsmc/master/ratings_train.txt
get raw_nsmc_test.txt    $R/e9t/nsmc/master/ratings_test.txt
get raw_chatbot.csv      $R/songys/Chatbot_data/master/ChatbotData.csv
get raw_hate.tsv         $R/kocohub/korean-hate-speech/master/labeled/train.tsv
get raw_unsmile.tsv      $R/smilegate-ai/korean_unsmile_dataset/main/unsmile_train_v1.0.tsv
get raw_3i4k.txt         $R/warnikchow/3i4k/master/data/train_val_test/fci_train_val.txt
# 번역문(KorNLI) — 맞춤법이 가장 깨끗하다. 잘못 고치는 비율을 재는 데도 쓴다.
get raw_KorNLI_snli.tsv  $R/kakaobrain/kor-nlu-datasets/master/KorNLI/snli_1.0_train.ko.tsv
get raw_KorNLI_mnli.tsv  $R/kakaobrain/kor-nlu-datasets/master/KorNLI/multinli.train.ko.tsv
echo "받은 것: $(ls | wc -l)개, $(du -sh . | cut -f1)"
