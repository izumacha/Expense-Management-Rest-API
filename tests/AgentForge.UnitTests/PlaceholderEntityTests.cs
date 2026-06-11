// ドメイン層への参照を取り込む
using AgentForge.Domain;
// 検証用のアサーションライブラリを取り込む
using Shouldly;
// テストフレームワークを取り込む
using Xunit;

// ユニットテストプロジェクトの名前空間
namespace AgentForge.UnitTests;

// PlaceholderEntity のプロパティ代入を検証するテストクラス
public sealed class PlaceholderEntityTests
{
    // コンストラクタでプロパティが正しく設定されることを検証する
    [Fact]
    public void Constructor_AssignsProperties()
    {
        // テスト対象のエンティティを生成する
        var entity = new PlaceholderEntity(Id: "id-1", Name: "alpha");

        // Id が設定した値と一致することを検証する
        entity.Id.ShouldBe("id-1");
        // Name が設定した値と一致することを検証する
        entity.Name.ShouldBe("alpha");
    }
}
